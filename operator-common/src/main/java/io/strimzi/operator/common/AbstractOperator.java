/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.WatcherException;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.strimzi.api.kafka.model.Spec;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.status.ConditionBuilder;
import io.strimzi.api.kafka.model.status.Status;
import io.strimzi.operator.cluster.model.InvalidResourceException;
import io.strimzi.operator.cluster.model.StatusDiff;
import io.strimzi.operator.common.metrics.OperatorMetricsHolder;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.model.NamespaceAndName;
import io.strimzi.operator.common.operator.resource.AbstractWatchableStatusedNamespacedResourceOperator;
import io.strimzi.operator.common.operator.resource.StatusUtils;
import io.strimzi.operator.common.operator.resource.TimeoutException;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.shareddata.Lock;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.strimzi.operator.common.Util.async;

/**
 * A base implementation of {@link Operator}.
 *
 * <ul>
 * <li>uses the Fabric8 kubernetes API and implements
 * {@link #reconcile(Reconciliation)} by delegating to abstract {@link #createOrUpdate(Reconciliation, CustomResource)}
 * and {@link #delete(Reconciliation)} methods for subclasses to implement.
 *
 * <li>add support for operator-side {@linkplain StatusUtils#validate(Reconciliation, CustomResource)} validation}.
 *     This can be used to automatically log warnings about source resources which used deprecated part of the CR API.
 *ą
 * </ul>
 * @param <T> The Java representation of the Kubernetes resource, e.g. {@code Kafka} or {@code KafkaConnect}
 * @param <O> The "Resource Operator" for the source resource type. Typically this will be some instantiation of
 *           {@link io.strimzi.operator.common.operator.resource.CrdOperator}.
 */
public abstract class AbstractOperator<
        T extends CustomResource<P, S>,
        P extends Spec,
        S extends Status,
        O extends AbstractWatchableStatusedNamespacedResourceOperator<?, T, ?, ?>>
            implements Operator {

    private static final ReconciliationLogger LOGGER = ReconciliationLogger.create(AbstractOperator.class);

    private static final long PROGRESS_WARNING = 60_000L;
    protected static final int LOCK_TIMEOUT_MS = 10000;

    /**
     * Prefix used for metrics provided by Strimzi operators
     */
    public static final String METRICS_PREFIX = "strimzi.";

    protected final Vertx vertx;
    protected final O resourceOperator;
    private final String kind;

    private final Optional<LabelSelector> selector;

    protected final OperatorMetricsHolder metrics;

    private Map<String, AtomicInteger> resourcesStateCounter = new ConcurrentHashMap<>(1);

    /**
     * Constructs the AbstractOperator. This constructor is used to construct the AbstractOperator using the
     * OperatorMetricsHolder instance. This constructor is used by subclasses which want to use specialized metrics
     * ¨holder such as the subclasses dealing with KafkaConnector resources and their metrics.
     *
     * @param vertx             Vert.x instance
     * @param kind              Resource kind which will be operated by this operator
     * @param resourceOperator  Resource operator for given custom resource
     * @param metrics           MetricsHolder for managing operator metrics
     * @param selectorLabels    Selector labels for selecting custom resources which should be operated
     */
    public AbstractOperator(Vertx vertx, String kind, O resourceOperator, OperatorMetricsHolder metrics, Labels selectorLabels) {
        this.vertx = vertx;
        this.kind = kind;
        this.resourceOperator = resourceOperator;
        this.selector = (selectorLabels == null || selectorLabels.toMap().isEmpty()) ? Optional.empty() : Optional.of(new LabelSelector(null, selectorLabels.toMap()));
        this.metrics = metrics;
    }

    /**
     * Constructs the AbstractOperator. This constructor is used to construct the AbstractOperator using the
     * MetricsProvider instance, which is used to create OperatorMetricsHolder inside the constructor. It is used by
     * subclasses which do not need a more specialized type of metrics holder.
     *
     * @param vertx             Vert.x instance
     * @param kind              Resource kind which will be operated by this operator
     * @param resourceOperator  Resource operator for given custom resource
     * @param metricsProvider   Metrics provider which should be used to create the OperatorMetricsHolder instance
     * @param selectorLabels    Selector labels for selecting custom resources which should be operated
     */
    public AbstractOperator(Vertx vertx, String kind, O resourceOperator, MetricsProvider metricsProvider, Labels selectorLabels) {
        this(vertx, kind, resourceOperator, new OperatorMetricsHolder(kind, selectorLabels, metricsProvider), selectorLabels);
    }

    @Override
    public String kind() {
        return kind;
    }

    @Override
    public OperatorMetricsHolder metrics()   {
        return metrics;
    }

    /**
     * Gets the name of the lock to be used for operating on the given {@code namespace} and
     * cluster {@code name}
     *
     * @param namespace The namespace containing the cluster
     * @param name The name of the cluster
     */
    /* test */ String getLockName(String namespace, String name) {
        return "lock::" + namespace + "::" + kind() + "::" + name;
    }

    /**
     * Asynchronously creates or updates the given {@code resource}.
     * This method can be called when the given {@code resource} has been created,
     * or updated and also at some regular interval while the resource continues to exist in Kubernetes.
     * The calling of this method does not imply that anything has actually changed.
     * @param reconciliation Uniquely identifies the reconciliation itself.
     * @param resource The resource to be created, or updated.
     * @return A Future which is completed once the reconciliation of the given resource instance is complete.
     */
    protected abstract Future<S> createOrUpdate(Reconciliation reconciliation, T resource);

    /**
     * Asynchronously deletes the resource identified by {@code reconciliation}.
     * Operators which only create other Kubernetes resources in order to honour their source resource can rely
     * on Kubernetes Garbage Collection to handle deletion.
     * Such operators should return a Future which completes with {@code false}.
     * Operators which handle deletion themselves should return a Future which completes with {@code true}.
     * @param reconciliation
     * @return
     */
    protected abstract Future<Boolean> delete(Reconciliation reconciliation);

    /**
     * Reconcile assembly resources in the given namespace having the given {@code name}.
     * Reconciliation works by getting the assembly resource (e.g. {@code KafkaUser})
     * in the given namespace with the given name and
     * comparing with the corresponding resource.
     * @param reconciliation The reconciliation.
     * @return A Future which is completed with the result of the reconciliation.
     */
    @Override
    @SuppressWarnings("unchecked")
    public final Future<Void> reconcile(Reconciliation reconciliation) {
        String namespace = reconciliation.namespace();
        String name = reconciliation.name();

        metrics().reconciliationsCounter(reconciliation.namespace()).increment();
        Timer.Sample reconciliationTimerSample = Timer.start(metrics().metricsProvider().meterRegistry());

        Future<Void> handler = withLock(reconciliation, LOCK_TIMEOUT_MS, () -> {
            T cr = resourceOperator.get(namespace, name);

            if (cr != null) {
                if (!Util.matchesSelector(selector(), cr))  {
                    // When the labels matching the selector are removed from the custom resource, a DELETE event is
                    // triggered by the watch even through the custom resource might not match the watch labels anymore
                    // and might not be really deleted. We have to filter these situations out and ignore the
                    // reconciliation because such resource might be already operated by another instance (where the
                    // same change triggered ADDED event).
                    LOGGER.debugCr(reconciliation, "{} {} in namespace {} does not match label selector {} and will be ignored", kind(), name, namespace, selector().get().getMatchLabels());
                    return Future.succeededFuture();
                }

                Promise<Void> createOrUpdate = Promise.promise();
                if (Annotations.isReconciliationPausedWithAnnotation(cr)) {
                    S status = createStatus();
                    Set<Condition> conditions = StatusUtils.validate(reconciliation, cr);
                    conditions.add(StatusUtils.getPausedCondition());
                    status.setConditions(new ArrayList<>(conditions));
                    status.setObservedGeneration(cr.getStatus() != null ? cr.getStatus().getObservedGeneration() : 0);

                    updateStatus(reconciliation, status).onComplete(statusResult -> {
                        if (statusResult.succeeded()) {
                            createOrUpdate.complete();
                        } else {
                            createOrUpdate.fail(statusResult.cause());
                        }
                    });
                    metrics().pausedResourceCounter(namespace).getAndIncrement();
                    LOGGER.infoCr(reconciliation, "Reconciliation of {} {} is paused", kind, name);
                    return createOrUpdate.future();
                } else if (cr.getSpec() == null) {
                    InvalidResourceException exception = new InvalidResourceException("Spec cannot be null");

                    S status = createStatus();
                    Condition errorCondition = new ConditionBuilder()
                            .withLastTransitionTime(StatusUtils.iso8601Now())
                            .withType("NotReady")
                            .withStatus("True")
                            .withReason(exception.getClass().getSimpleName())
                            .withMessage(exception.getMessage())
                            .build();
                    status.setObservedGeneration(cr.getMetadata().getGeneration());
                    status.addCondition(errorCondition);

                    LOGGER.errorCr(reconciliation, "{} spec cannot be null", cr.getMetadata().getName());
                    updateStatus(reconciliation, status).onComplete(notUsed -> {
                        createOrUpdate.fail(exception);
                    });

                    return createOrUpdate.future();
                }

                Set<Condition> unknownAndDeprecatedConditions = StatusUtils.validate(reconciliation, cr);

                LOGGER.infoCr(reconciliation, "{} {} will be checked for creation or modification", kind, name);

                createOrUpdate(reconciliation, cr)
                        .onComplete(res -> {
                            if (res.succeeded()) {
                                S status = res.result();

                                StatusUtils.addConditionsToStatus(status, unknownAndDeprecatedConditions);
                                updateStatus(reconciliation, status).onComplete(statusResult -> {
                                    if (statusResult.succeeded()) {
                                        createOrUpdate.complete();
                                    } else {
                                        createOrUpdate.fail(statusResult.cause());
                                    }
                                });
                            } else {
                                if (res.cause() instanceof ReconciliationException) {
                                    ReconciliationException e = (ReconciliationException) res.cause();
                                    Status status = e.getStatus();
                                    StatusUtils.addConditionsToStatus(status, unknownAndDeprecatedConditions);

                                    LOGGER.errorCr(reconciliation, "createOrUpdate failed", e.getCause());

                                    updateStatus(reconciliation, (S) status).onComplete(statusResult -> {
                                        createOrUpdate.fail(e.getCause());
                                    });
                                } else {
                                    LOGGER.errorCr(reconciliation, "createOrUpdate failed", res.cause());
                                    createOrUpdate.fail(res.cause());
                                }
                            }
                        });

                return createOrUpdate.future();
            } else {
                LOGGER.infoCr(reconciliation, "{} {} should be deleted", kind, name);
                return delete(reconciliation).map(deleteResult -> {
                    if (deleteResult) {
                        LOGGER.infoCr(reconciliation, "{} {} deleted", kind, name);
                    } else {
                        LOGGER.infoCr(reconciliation, "Assembly {} or some parts of it will be deleted by garbage collection", name);
                    }
                    return (Void) null;
                }).recover(deleteResult -> {
                    LOGGER.errorCr(reconciliation, "Deletion of {} {} failed", kind, name, deleteResult);
                    return Future.failedFuture(deleteResult);
                });
            }
        });

        Promise<Void> result = Promise.promise();
        handler.onComplete(reconcileResult -> {
            try {
                handleResult(reconciliation, reconcileResult, reconciliationTimerSample);
            } finally {
                result.handle(reconcileResult);
            }
        });

        return result.future();
    }

    /**
     * Updates the Status field of the Kafka CR. It diffs the desired status against the current status and calls
     * the update only when there is any difference in non-timestamp fields.
     *
     * @param reconciliation the reconciliation identified
     * @param desiredStatus The KafkaStatus which should be set
     *
     * @return
     */
    Future<Void> updateStatus(Reconciliation reconciliation, S desiredStatus) {
        if (desiredStatus == null)  {
            LOGGER.debugCr(reconciliation, "Desired status is null - status will not be updated");
            return Future.succeededFuture();
        }

        String namespace = reconciliation.namespace();
        String name = reconciliation.name();

        return resourceOperator.getAsync(namespace, name)
                .compose(res -> {
                    if (res != null) {
                        S currentStatus = res.getStatus();
                        StatusDiff sDiff = new StatusDiff(currentStatus, desiredStatus);

                        if (!sDiff.isEmpty()) {
                            res.setStatus(desiredStatus);

                            return resourceOperator.updateStatusAsync(reconciliation, res)
                                    .compose(notUsed -> {
                                        LOGGER.debugCr(reconciliation, "Completed status update");
                                        return Future.succeededFuture();
                                    }, error -> {
                                            LOGGER.errorCr(reconciliation, "Failed to update status", error);
                                            return Future.failedFuture(error);
                                        });
                        } else {
                            LOGGER.debugCr(reconciliation, "Status did not change");
                            return Future.succeededFuture();
                        }
                    } else {
                        LOGGER.errorCr(reconciliation, "Current {} resource not found", reconciliation.kind());
                        return Future.failedFuture("Current " + reconciliation.kind() + " resource with name " + name + " not found");
                    }
                }, error -> {
                        LOGGER.errorCr(reconciliation, "Failed to get the current {} resource and its status", reconciliation.kind(), error);
                        return Future.failedFuture(error);
                    });
    }

    protected abstract S createStatus();

    /**
     * The exception by which Futures returned by {@link #withLock(Reconciliation, long, Callable)} are failed when
     * the lock cannot be acquired within the timeout.
     */
    static class UnableToAcquireLockException extends TimeoutException { }

    /**
     * Acquire the lock for the resource implied by the {@code reconciliation}
     * and call the given {@code callable} with the lock held.
     * Once the callable returns (or if it throws) release the lock and complete the returned Future.
     * If the lock cannot be acquired the given {@code callable} is not called and the returned Future is completed with {@link UnableToAcquireLockException}.
     * @param reconciliation
     * @param callable
     * @param <T>
     * @return
     */
    protected final <T> Future<T> withLock(Reconciliation reconciliation, long lockTimeoutMs, Callable<Future<T>> callable) {
        Promise<T> handler = Promise.promise();
        String namespace = reconciliation.namespace();
        String name = reconciliation.name();
        final String lockName = getLockName(namespace, name);
        LOGGER.debugCr(reconciliation, "Try to acquire lock {}", lockName);
        vertx.sharedData().getLockWithTimeout(lockName, lockTimeoutMs, res -> {
            if (res.succeeded()) {
                LOGGER.debugCr(reconciliation, "Lock {} acquired", lockName);

                Lock lock = res.result();
                long timerId = vertx.setPeriodic(PROGRESS_WARNING, timer -> {
                    LOGGER.infoCr(reconciliation, "Reconciliation is in progress");
                });

                callSafely(reconciliation, callable)
                    .onSuccess(handleSafely(reconciliation, handler::complete))
                    .onFailure(handleSafely(reconciliation, handler::fail))
                    .eventually(ignored -> releaseLockAndTimer(reconciliation, lock, lockName, timerId));
            } else {
                LOGGER.debugCr(reconciliation, "Failed to acquire lock {} within {}ms.", lockName, lockTimeoutMs);
                handler.fail(new UnableToAcquireLockException());
            }
        });
        return handler.future();
    }

    /**
     * Safely executes <code>callable</code>, always returning a
     * <code>Future</code>. In the context of this method, the term "safely"
     * indicates that exceptions thrown by <code>callable</code> will result in the
     * return of a failed <code>Future</code>.
     *
     * @param <C>            result type of the <code>Future</code> returned by the
     *                       provided <code>callable</code>
     * @param reconciliation the reconciliation being processed by
     *                       <code>callable</code>
     * @param callable       callable routine for processing the reconciliation
     *
     * @return the result of <code>callable</code> or a failed <code>Future</code>
     *         when an exception is thrown
     */
    private <C> Future<C> callSafely(Reconciliation reconciliation, Callable<Future<C>> callable) {
        try {
            return callable.call();
        } catch (Throwable ex) {
            LOGGER.errorCr(reconciliation, "Reconciliation failed", ex);
            return Future.failedFuture(ex);
        }
    }

    /**
     * Provides a proxy <code>Handler</code> that will safely execute the given
     * <code>handler</code>. In the context of this method, the term "safely"
     * indicates that exceptions thrown by <code>handler</code> will be caught and
     * logged, and not thrown to the caller.
     *
     * @param <H>            argument type of the <code>Handler</code>
     * @param reconciliation the reconciliation being processed, the context of the
     *                       operation
     * @param handler        handler, for either success or failure
     *
     * @return a proxy <code>Handler</code> that, when executed, will execute the
     *         provided handler, ensuring that unhandled exceptions are caught and
     *         logged.
     */
    private <H> Handler<H> handleSafely(Reconciliation reconciliation, Handler<H> handler) {
        return result -> {
            try {
                handler.handle(result);
            } catch (Throwable ex) {
                LOGGER.errorCr(reconciliation, "Reconciliation completion handler failed", ex);
            }
        };
    }

    private Future<Void> releaseLockAndTimer(Reconciliation reconciliation, Lock lock, String lockName, long timerId) {
        vertx.cancelTimer(timerId);
        lock.release();
        LOGGER.debugCr(reconciliation, "Lock {} released", lockName);
        return Future.succeededFuture();
    }

    public Future<Set<NamespaceAndName>> allResourceNames(String namespace) {
        return resourceOperator.listAsync(namespace, selector())
                .map(resourceList ->
                        resourceList.stream()
                                .map(resource -> new NamespaceAndName(resource.getMetadata().getNamespace(), resource.getMetadata().getName()))
                                .collect(Collectors.toSet()));
    }

    /**
     * A selector to narrow the scope of the {@linkplain #createWatch(String, Consumer) watch}
     * and {@linkplain #allResourceNames(String) query}.
     * @return A selector.
     */
    public Optional<LabelSelector> selector() {
        return selector;
    }

    /**
     * Create Kubernetes watch.
     *
     * @param namespace Namespace where to watch for users.
     * @param onClose Callback called when the watch is closed.
     *
     * @return A future which completes when the watcher has been created.
     */
    public Future<Watch> createWatch(String namespace, Consumer<WatcherException> onClose) {
        return async(vertx, () -> resourceOperator.watch(namespace, selector(), new OperatorWatcher<>(this, namespace, onClose)));
    }

    /**
     * Recreates a Kubernetes watch
     *
     * @param namespace Namespace which should be watched
     *
     * @return  Consumer for a Watched exception
     */
    public Consumer<WatcherException> recreateWatch(String namespace) {
        Consumer<WatcherException> kubernetesClientExceptionConsumer = new Consumer<WatcherException>() {
            @Override
            public void accept(WatcherException e) {
                if (e != null) {
                    LOGGER.errorOp("Watcher closed with exception in namespace {}", namespace, e);
                    createWatch(namespace, this);
                } else {
                    LOGGER.infoOp("Watcher closed in namespace {}", namespace);
                }
            }
        };
        return kubernetesClientExceptionConsumer;
    }

    /**
     * Log the reconciliation outcome.
     */
    private void handleResult(Reconciliation reconciliation, AsyncResult<Void> result, Timer.Sample reconciliationTimerSample) {
        if (result.succeeded()) {
            updateResourceState(reconciliation, true, null);
            metrics().successfulReconciliationsCounter(reconciliation.namespace()).increment();
            reconciliationTimerSample.stop(metrics().reconciliationsTimer(reconciliation.namespace()));
            LOGGER.infoCr(reconciliation, "reconciled");
        } else {
            Throwable cause = result.cause();

            if (cause instanceof InvalidConfigParameterException) {
                updateResourceState(reconciliation, false, cause);
                metrics().failedReconciliationsCounter(reconciliation.namespace()).increment();
                reconciliationTimerSample.stop(metrics().reconciliationsTimer(reconciliation.namespace()));
                LOGGER.warnCr(reconciliation, "Failed to reconcile {}", cause.getMessage());
            } else if (cause instanceof UnableToAcquireLockException) {
                metrics().lockedReconciliationsCounter(reconciliation.namespace()).increment();
            } else  {
                updateResourceState(reconciliation, false, cause);
                metrics().failedReconciliationsCounter(reconciliation.namespace()).increment();
                reconciliationTimerSample.stop(metrics().reconciliationsTimer(reconciliation.namespace()));
                LOGGER.warnCr(reconciliation, "Failed to reconcile", cause);
            }
        }
    }

    /**
     * Updates the resource state metric for the provided reconciliation which brings kind, name and namespace
     * of the custom resource.
     *
     * @param reconciliation reconciliation to use to update the resource state metric
     * @param ready if reconcile was successful and the resource is ready
     */
    private void updateResourceState(Reconciliation reconciliation, boolean ready, Throwable cause) {
        String key = reconciliation.namespace() + ":" + reconciliation.kind() + "/" + reconciliation.name();

        Tags metricTags = Tags.of(
                    Tag.of("kind", reconciliation.kind()),
                    Tag.of("name", reconciliation.name()),
                    Tag.of("resource-namespace", reconciliation.namespace()),
                    Tag.of("reason", cause == null ? "none" : cause.getMessage() == null ? "unknown error" : cause.getMessage()));

        T cr = resourceOperator.get(reconciliation.namespace(), reconciliation.name());

        Optional<Meter> metric = metrics().metricsProvider().meterRegistry().getMeters()
                .stream()
                .filter(meter -> meter.getId().getName().equals(METRICS_PREFIX + "resource.state") &&
                        meter.getId().getTags().contains(Tag.of("kind", reconciliation.kind())) &&
                        meter.getId().getTags().contains(Tag.of("name", reconciliation.name())) &&
                        meter.getId().getTags().contains(Tag.of("resource-namespace", reconciliation.namespace()))
                ).findFirst();

        if (metric.isPresent()) {
            // remove metric so it can be re-added with new tags
            metrics().metricsProvider().meterRegistry().remove(metric.get().getId());
            resourcesStateCounter.remove(key);
            LOGGER.debugCr(reconciliation, "Removed metric " + METRICS_PREFIX + "resource.state{}", key);
        }

        if (cr != null && Util.matchesSelector(selector(), cr)) {
            resourcesStateCounter.computeIfAbsent(key, tags ->
                    metrics().metricsProvider().gauge(METRICS_PREFIX + "resource.state", "Current state of the resource: 1 ready, 0 fail", metricTags)
            );
            resourcesStateCounter.get(key).set(ready ? 1 : 0);
            LOGGER.debugCr(reconciliation, "Updated metric " + METRICS_PREFIX + "resource.state{} = {}", metricTags, ready ? 1 : 0);
        }
    }
}
