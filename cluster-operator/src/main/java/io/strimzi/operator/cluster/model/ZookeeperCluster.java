/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.strimzi.api.kafka.model.JvmOptions;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaClusterSpec;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.Probe;
import io.strimzi.api.kafka.model.ProbeBuilder;
import io.strimzi.api.kafka.model.StrimziPodSet;
import io.strimzi.api.kafka.model.ZookeeperClusterSpec;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.api.kafka.model.template.InternalServiceTemplate;
import io.strimzi.api.kafka.model.template.PodDisruptionBudgetTemplate;
import io.strimzi.api.kafka.model.template.PodTemplate;
import io.strimzi.api.kafka.model.template.ResourceTemplate;
import io.strimzi.api.kafka.model.template.StatefulSetTemplate;
import io.strimzi.api.kafka.model.template.ZookeeperClusterTemplate;
import io.strimzi.certs.CertAndKey;
import io.strimzi.operator.cluster.model.jmx.JmxModel;
import io.strimzi.operator.cluster.model.jmx.SupportsJmx;
import io.strimzi.operator.cluster.model.logging.LoggingModel;
import io.strimzi.operator.cluster.model.logging.SupportsLogging;
import io.strimzi.operator.cluster.model.metrics.MetricsModel;
import io.strimzi.operator.cluster.model.metrics.SupportsMetrics;
import io.strimzi.operator.cluster.model.securityprofiles.ContainerSecurityProviderContextImpl;
import io.strimzi.operator.cluster.model.securityprofiles.PodSecurityProviderContextImpl;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.MetricsAndLogging;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.resource.StatusUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.Collections.emptyMap;

/**
 * ZooKeeper cluster model
 */
@SuppressWarnings({"checkstyle:ClassFanOutComplexity"})
public class ZookeeperCluster extends AbstractStatefulModel implements SupportsMetrics, SupportsLogging, SupportsJmx {
    /**
     * Port for plaintext access for ZooKeeper clients (available inside the pod only)
     */
    public static final int CLIENT_PLAINTEXT_PORT = 12181; // This port is internal only, not exposed => no need for name

    /**
     * TLS port for ZooKeeper clients
     */
    public static final int CLIENT_TLS_PORT = 2181;

    /**
     * Port used for ZooKeeper clustering
     */
    public static final int CLUSTERING_PORT = 2888;

    /**
     * Port used for ZooKeeper leader election
     */
    public static final int LEADER_ELECTION_PORT = 3888;

    protected static final String COMPONENT_TYPE = "zookeeper";
    protected static final String CLIENT_TLS_PORT_NAME = "tcp-clients";
    protected static final String CLUSTERING_PORT_NAME = "tcp-clustering";
    protected static final String LEADER_ELECTION_PORT_NAME = "tcp-election";

    protected static final String ZOOKEEPER_NAME = "zookeeper";
    protected static final String ZOOKEEPER_NODE_CERTIFICATES_VOLUME_NAME = "zookeeper-nodes";
    protected static final String ZOOKEEPER_NODE_CERTIFICATES_VOLUME_MOUNT = "/opt/kafka/zookeeper-node-certs/";
    protected static final String ZOOKEEPER_CLUSTER_CA_VOLUME_NAME = "cluster-ca-certs";
    protected static final String ZOOKEEPER_CLUSTER_CA_VOLUME_MOUNT = "/opt/kafka/cluster-ca-certs/";

    // Zookeeper configuration
    private final boolean isSnapshotCheckEnabled;
    private JmxModel jmx;
    @SuppressFBWarnings({"UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"}) // This field is initialized in the fromCrd method
    private MetricsModel metrics;
    private LoggingModel logging;

    private static final Probe DEFAULT_HEALTHCHECK_OPTIONS = new ProbeBuilder()
            .withTimeoutSeconds(5)
            .withInitialDelaySeconds(15)
            .build();
    private static final boolean DEFAULT_ZOOKEEPER_SNAPSHOT_CHECK_ENABLED = true;

    // Zookeeper configuration keys (EnvVariables)
    protected static final String ENV_VAR_ZOOKEEPER_METRICS_ENABLED = "ZOOKEEPER_METRICS_ENABLED";
    protected static final String ENV_VAR_ZOOKEEPER_CONFIGURATION = "ZOOKEEPER_CONFIGURATION";
    private static final String ENV_VAR_ZOOKEEPER_SNAPSHOT_CHECK_ENABLED = "ZOOKEEPER_SNAPSHOT_CHECK_ENABLED";

    protected static final String CO_ENV_VAR_CUSTOM_ZOOKEEPER_POD_LABELS = "STRIMZI_CUSTOM_ZOOKEEPER_LABELS";

    // Config map keys
    private static final String CONFIG_MAP_KEY_ZOOKEEPER_NODE_COUNT = "zookeeper.node-count";

    // Templates
    private PodDisruptionBudgetTemplate templatePodDisruptionBudget;
    private ResourceTemplate templatePersistentVolumeClaims;
    private StatefulSetTemplate templateStatefulSet;
    private ResourceTemplate templatePodSet;
    private PodTemplate templatePod;
    private InternalServiceTemplate templateHeadlessService;
    private InternalServiceTemplate templateService;

    private static final Map<String, String> DEFAULT_POD_LABELS = new HashMap<>();
    static {
        String value = System.getenv(CO_ENV_VAR_CUSTOM_ZOOKEEPER_POD_LABELS);
        if (value != null) {
            DEFAULT_POD_LABELS.putAll(Util.parseMap(value));
        }
    }

    /**
     * Constructor
     *
     * @param reconciliation The reconciliation
     * @param resource Kubernetes resource with metadata containing the namespace and cluster name
     */
    private ZookeeperCluster(Reconciliation reconciliation, HasMetadata resource) {
        super(reconciliation, resource, KafkaResources.zookeeperStatefulSetName(resource.getMetadata().getName()), COMPONENT_TYPE);

        this.image = null;
        this.replicas = ZookeeperClusterSpec.DEFAULT_REPLICAS;
        this.readinessPath = "/opt/kafka/zookeeper_healthcheck.sh";
        this.readinessProbeOptions = DEFAULT_HEALTHCHECK_OPTIONS;
        this.livenessPath = "/opt/kafka/zookeeper_healthcheck.sh";
        this.livenessProbeOptions = DEFAULT_HEALTHCHECK_OPTIONS;
        this.isSnapshotCheckEnabled = DEFAULT_ZOOKEEPER_SNAPSHOT_CHECK_ENABLED;

        this.mountPath = "/var/lib/zookeeper";

        this.logAndMetricsConfigVolumeName = "zookeeper-metrics-and-logging";
        this.logAndMetricsConfigMountPath = "/opt/kafka/custom-config/";
    }

    /**
     * Creates ZooKeeper cluster model from the Kafka CR
     *
     * @param reconciliation    Reconciliation marker
     * @param kafkaAssembly     The Kafka CR
     * @param versions          Supported Kafka versions
     *
     * @return  New instance of the ZooKeeper cluster model
     */
    public static ZookeeperCluster fromCrd(Reconciliation reconciliation, Kafka kafkaAssembly, KafkaVersion.Lookup versions) {
        return fromCrd(reconciliation, kafkaAssembly, versions, null, 0);
    }

    /**
     * Creates ZooKeeper cluster model from the Kafka CR
     *
     * @param reconciliation    Reconciliation marker
     * @param kafkaAssembly     The Kafka CR
     * @param versions          Supported Kafka versions
     * @param oldStorage        Old storage configuration (based on the actual Kubernetes cluster)
     * @param oldReplicas       Current number of replicas (based on the actual Kubernetes cluster)
     *
     * @return  New instance of the ZooKeeper cluster model
     */
    @SuppressWarnings({"checkstyle:MethodLength", "checkstyle:CyclomaticComplexity", "checkstyle:NPathComplexity"})
    public static ZookeeperCluster fromCrd(Reconciliation reconciliation, Kafka kafkaAssembly, KafkaVersion.Lookup versions, Storage oldStorage, int oldReplicas) {
        ZookeeperCluster result = new ZookeeperCluster(reconciliation, kafkaAssembly);
        ZookeeperClusterSpec zookeeperClusterSpec = kafkaAssembly.getSpec().getZookeeper();

        int replicas = zookeeperClusterSpec.getReplicas();
        if (replicas <= 0) {
            replicas = ZookeeperClusterSpec.DEFAULT_REPLICAS;
        }
        if (replicas == 1 && zookeeperClusterSpec.getStorage() != null && "ephemeral".equals(zookeeperClusterSpec.getStorage().getType())) {
            LOGGER.warnCr(reconciliation, "A ZooKeeper cluster with a single replica and ephemeral storage will be in a defective state after any restart or rolling update. It is recommended that a minimum of three replicas are used.");
        }
        result.replicas = replicas;

        ModelUtils.validateComputeResources(zookeeperClusterSpec.getResources(), ".spec.zookeeper.resources");

        String image = zookeeperClusterSpec.getImage();
        if (image == null) {
            KafkaClusterSpec kafkaClusterSpec = kafkaAssembly.getSpec().getKafka();
            image = versions.kafkaImage(kafkaClusterSpec != null ? kafkaClusterSpec.getImage() : null,
                    kafkaClusterSpec != null ? kafkaClusterSpec.getVersion() : null);
        }
        result.image = image;

        if (zookeeperClusterSpec.getReadinessProbe() != null) {
            result.readinessProbeOptions = zookeeperClusterSpec.getReadinessProbe();
        }
        if (zookeeperClusterSpec.getLivenessProbe() != null) {
            result.livenessProbeOptions = zookeeperClusterSpec.getLivenessProbe();
        }

        result.gcLoggingEnabled = zookeeperClusterSpec.getJvmOptions() == null ? JvmOptions.DEFAULT_GC_LOGGING_ENABLED : zookeeperClusterSpec.getJvmOptions().isGcLoggingEnabled();

        if (oldStorage != null) {
            Storage newStorage = zookeeperClusterSpec.getStorage();
            StorageUtils.validatePersistentStorage(newStorage);

            StorageDiff diff = new StorageDiff(reconciliation, oldStorage, newStorage, oldReplicas, zookeeperClusterSpec.getReplicas());

            if (!diff.isEmpty()) {
                LOGGER.warnCr(reconciliation, "Only the following changes to Zookeeper storage are allowed: " +
                        "changing the deleteClaim flag, " +
                        "changing overrides to nodes which do not exist yet " +
                        "and increasing size of persistent claim volumes (depending on the volume type and used storage class).");
                LOGGER.warnCr(reconciliation, "The desired ZooKeeper storage configuration in the custom resource {}/{} contains changes which are not allowed. As " +
                        "a result, all storage changes will be ignored. Use DEBUG level logging for more information " +
                        "about the detected changes.", kafkaAssembly.getMetadata().getNamespace(), kafkaAssembly.getMetadata().getName());

                Condition warning = StatusUtils.buildWarningCondition("ZooKeeperStorage",
                        "The desired ZooKeeper storage configuration contains changes which are not allowed. As a " +
                                "result, all storage changes will be ignored. Use DEBUG level logging for more information " +
                                "about the detected changes.");
                result.warningConditions.add(warning);

                result.setStorage(oldStorage);
            } else {
                result.setStorage(newStorage);
            }
        } else {
            result.setStorage(zookeeperClusterSpec.getStorage());
        }

        result.setConfiguration(new ZookeeperConfiguration(reconciliation, zookeeperClusterSpec.getConfig().entrySet()));

        result.resources = zookeeperClusterSpec.getResources();

        result.jvmOptions = zookeeperClusterSpec.getJvmOptions();
        result.metrics = new MetricsModel(zookeeperClusterSpec);
        result.logging = new LoggingModel(zookeeperClusterSpec, result.getClass().getSimpleName(), false, false);
        result.jmx = new JmxModel(
                reconciliation.namespace(),
                KafkaResources.zookeeperJmxSecretName(result.cluster),
                result.labels,
                result.ownerReference,
                zookeeperClusterSpec
        );

        if (zookeeperClusterSpec.getTemplate() != null) {
            ZookeeperClusterTemplate template = zookeeperClusterSpec.getTemplate();

            result.templatePodDisruptionBudget = template.getPodDisruptionBudget();
            result.templatePersistentVolumeClaims = template.getPersistentVolumeClaim();
            result.templateStatefulSet = template.getStatefulset();
            result.templatePodSet = template.getPodSet();
            result.templatePod = template.getPod();
            result.templateService = template.getClientService();
            result.templateHeadlessService = template.getNodesService();
            result.templateServiceAccount = template.getServiceAccount();
            result.templateContainer = template.getZookeeperContainer();
        }

        // Should run at the end when everything is set
        ZooKeeperSpecChecker specChecker = new ZooKeeperSpecChecker(result);
        result.warningConditions.addAll(specChecker.run());

        return result;
    }

    /**
     * @return  Generates a ZooKeeper service
     */
    public Service generateService() {
        return ServiceUtils.createClusterIpService(
                KafkaResources.zookeeperServiceName(cluster),
                namespace,
                labels,
                ownerReference,
                templateService,
                List.of(ServiceUtils.createServicePort(CLIENT_TLS_PORT_NAME, CLIENT_TLS_PORT, CLIENT_TLS_PORT, "TCP"))
        );
    }

    /**
     * Generates the NetworkPolicies relevant for ZooKeeper nodes
     *
     * @param operatorNamespace                             Namespace where the Strimzi Cluster Operator runs. Null if not configured.
     * @param operatorNamespaceLabels                       Labels of the namespace where the Strimzi Cluster Operator runs. Null if not configured.
     *
     * @return The network policy.
     */
    public NetworkPolicy generateNetworkPolicy(String operatorNamespace, Labels operatorNamespaceLabels) {
        // Internal peers => Strimzi components which need access
        NetworkPolicyPeer clusterOperatorPeer = NetworkPolicyUtils.createPeer(Map.of(Labels.STRIMZI_KIND_LABEL, "cluster-operator"), NetworkPolicyUtils.clusterOperatorNamespaceSelector(namespace, operatorNamespace, operatorNamespaceLabels));
        NetworkPolicyPeer zookeeperClusterPeer = NetworkPolicyUtils.createPeer(labels.strimziSelectorLabels().toMap());
        NetworkPolicyPeer kafkaClusterPeer = NetworkPolicyUtils.createPeer(Map.of(Labels.STRIMZI_NAME_LABEL, KafkaResources.kafkaStatefulSetName(cluster)));
        NetworkPolicyPeer entityOperatorPeer = NetworkPolicyUtils.createPeer(Map.of(Labels.STRIMZI_NAME_LABEL, KafkaResources.entityOperatorDeploymentName(cluster)));

        // List of network policy rules for all ports
        List<NetworkPolicyIngressRule> rules = new ArrayList<>();

        // Zookeeper only ports - 2888 & 3888 which need to be accessed by the Zookeeper cluster members only
        rules.add(NetworkPolicyUtils.createIngressRule(CLUSTERING_PORT, List.of(zookeeperClusterPeer)));
        rules.add(NetworkPolicyUtils.createIngressRule(LEADER_ELECTION_PORT, List.of(zookeeperClusterPeer)));

        // Clients port - needs to be access from outside the Zookeeper cluster as well
        rules.add(NetworkPolicyUtils.createIngressRule(CLIENT_TLS_PORT, List.of(kafkaClusterPeer, zookeeperClusterPeer, entityOperatorPeer, clusterOperatorPeer)));

        // The Metrics port (if enabled) is opened to all by default
        if (metrics.isEnabled()) {
            rules.add(NetworkPolicyUtils.createIngressRule(MetricsModel.METRICS_PORT, List.of()));
        }

        // The JMX port (if enabled) is opened to all by default
        rules.addAll(jmx.networkPolicyIngresRules());

        // Build the final network policy with all rules covering all the ports
        return NetworkPolicyUtils.createNetworkPolicy(
                KafkaResources.zookeeperNetworkPolicyName(cluster),
                namespace,
                labels,
                ownerReference,
                rules
        );
    }

    /**
     * @return  Generates the headless ZooKeeper service
     */
    public Service generateHeadlessService() {
        return ServiceUtils.createHeadlessService(
                KafkaResources.zookeeperHeadlessServiceName(cluster),
                namespace,
                labels,
                ownerReference,
                templateHeadlessService,
                getServicePortList()
        );
    }

    /**
     * Generates ZooKeeper StatefulSet
     *
     * @param isOpenShift       Flag indicating if we are on OpenShift or not
     * @param imagePullPolicy   Image pull policy
     * @param imagePullSecrets  List of image pull secrets
     *
     * @return  Generated StatefulSet
     */
    public StatefulSet generateStatefulSet(boolean isOpenShift, ImagePullPolicy imagePullPolicy, List<LocalObjectReference> imagePullSecrets) {
        return WorkloadUtils.createStatefulSet(
                componentName,
                namespace,
                labels,
                ownerReference,
                templateStatefulSet,
                replicas,
                KafkaResources.zookeeperHeadlessServiceName(cluster),
                Map.of(Annotations.ANNO_STRIMZI_IO_STORAGE, ModelUtils.encodeStorageToJson(storage)),
                getPersistentVolumeClaimTemplates(),
                WorkloadUtils.createPodTemplateSpec(
                        componentName,
                        labels,
                        templatePod,
                        DEFAULT_POD_LABELS,
                        Map.of(),
                        templatePod != null ? templatePod.getAffinity() : null,
                        null,
                        List.of(createContainer(imagePullPolicy)),
                        getStatefulSetVolumes(isOpenShift),
                        imagePullSecrets,
                        securityProvider.zooKeeperPodSecurityContext(new PodSecurityProviderContextImpl(storage, templatePod))
                )
        );
    }

    /**
     * Generates the StrimziPodSet for the ZooKeeper cluster. This is used when the UseStrimziPodSets feature gate is
     * enabled.
     *
     * @param replicas                  Number of replicas the StrimziPodSet should have. During scale-ups or scale-downs,
     *                                  node sets with different numbers of pods are generated.
     * @param isOpenShift               Flags whether we are on OpenShift or not
     * @param imagePullPolicy           Image pull policy which will be used by the pods
     * @param imagePullSecrets          List of image pull secrets
     * @param podAnnotationsProvider    Function which provides the annotations for the given pod based on its index.
     *                                  The annotations for each pod are different due to different certificates. So they
     *                                  need to be dynamically generated though this function instead of just
     *                                  passed as Map.
     *
     * @return                  Generated StrimziPodSet with ZooKeeper pods
     */
    public StrimziPodSet generatePodSet(int replicas,
                                        boolean isOpenShift,
                                        ImagePullPolicy imagePullPolicy,
                                        List<LocalObjectReference> imagePullSecrets,
                                        Function<Integer, Map<String, String>> podAnnotationsProvider) {
        return WorkloadUtils.createPodSet(
                componentName,
                namespace,
                labels,
                ownerReference,
                templatePodSet,
                replicas,
                Map.of(Annotations.ANNO_STRIMZI_IO_STORAGE, ModelUtils.encodeStorageToJson(storage)),
                labels.strimziSelectorLabels(),
                podNum -> WorkloadUtils.createStatefulPod(
                        reconciliation,
                        getPodName(podNum),
                        namespace,
                        labels,
                        componentName,
                        componentName,
                        templatePod,
                        DEFAULT_POD_LABELS,
                        podAnnotationsProvider.apply(podNum),
                        KafkaResources.zookeeperHeadlessServiceName(cluster),
                        templatePod != null ? templatePod.getAffinity() : null,
                        null,
                        List.of(createContainer(imagePullPolicy)),
                        getPodSetVolumes(getPodName(podNum), isOpenShift),
                        imagePullSecrets,
                        securityProvider.zooKeeperPodSecurityContext(new PodSecurityProviderContextImpl(storage, templatePod))
                )
        );
    }

    /**
     * Generate the Secret containing the Zookeeper nodes certificates signed by the cluster CA certificate used for TLS
     * based internal communication with Kafka. It contains both the public and private keys.
     *
     * @param clusterCa                         The CA for cluster certificates
     * @param isMaintenanceTimeWindowsSatisfied Indicates whether we are in the maintenance window or not.
     *
     * @return The generated Secret with the ZooKeeper node certificates
     */
    public Secret generateCertificatesSecret(ClusterCa clusterCa, boolean isMaintenanceTimeWindowsSatisfied) {
        Map<String, String> secretData = new HashMap<>(replicas * 4);
        Map<String, CertAndKey> certs;

        try {
            certs = clusterCa.generateZkCerts(namespace, cluster, replicas, isMaintenanceTimeWindowsSatisfied);
        } catch (IOException e) {
            LOGGER.warnCr(reconciliation, "Error while generating certificates", e);
            throw new RuntimeException("Failed to prepare ZooKeeper certificates", e);
        }

        for (int i = 0; i < replicas; i++) {
            CertAndKey cert = certs.get(KafkaResources.zookeeperPodName(cluster, i));
            secretData.put(KafkaResources.zookeeperPodName(cluster, i) + ".key", cert.keyAsBase64String());
            secretData.put(KafkaResources.zookeeperPodName(cluster, i) + ".crt", cert.certAsBase64String());
            secretData.put(KafkaResources.zookeeperPodName(cluster, i) + ".p12", cert.keyStoreAsBase64String());
            secretData.put(KafkaResources.zookeeperPodName(cluster, i) + ".password", cert.storePasswordAsBase64String());
        }

        return ModelUtils.createSecret(
                KafkaResources.zookeeperSecretName(cluster),
                namespace,
                labels,
                ownerReference,
                secretData,
                Map.of(clusterCa.caCertGenerationAnnotation(), String.valueOf(clusterCa.certGeneration())),
                emptyMap()
        );
    }

    /* test */ Container createContainer(ImagePullPolicy imagePullPolicy) {
        return ContainerUtils.createContainer(
                ZOOKEEPER_NAME,
                image,
                List.of("/opt/kafka/zookeeper_run.sh"),
                securityProvider.zooKeeperContainerSecurityContext(new ContainerSecurityProviderContextImpl(storage, templateContainer)),
                resources,
                getEnvVars(),
                getContainerPortList(),
                getVolumeMounts(),
                ProbeGenerator.execProbe(livenessProbeOptions, Collections.singletonList(livenessPath)),
                ProbeGenerator.execProbe(readinessProbeOptions, Collections.singletonList(readinessPath)),
                imagePullPolicy
        );
    }

    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(ContainerUtils.createEnvVar(ENV_VAR_ZOOKEEPER_METRICS_ENABLED, String.valueOf(metrics.isEnabled())));
        varList.add(ContainerUtils.createEnvVar(ENV_VAR_ZOOKEEPER_SNAPSHOT_CHECK_ENABLED, String.valueOf(isSnapshotCheckEnabled)));
        varList.add(ContainerUtils.createEnvVar(ENV_VAR_STRIMZI_KAFKA_GC_LOG_ENABLED, String.valueOf(gcLoggingEnabled)));

        varList.addAll(jmx.envVars());

        ModelUtils.heapOptions(varList, 75, 2L * 1024L * 1024L * 1024L, jvmOptions, resources);
        ModelUtils.jvmPerformanceOptions(varList, jvmOptions);
        ModelUtils.jvmSystemProperties(varList, jvmOptions);
        varList.add(ContainerUtils.createEnvVar(ENV_VAR_ZOOKEEPER_CONFIGURATION, configuration.getConfiguration()));

        // Add shared environment variables used for all containers
        varList.addAll(ContainerUtils.requiredEnvVars());

        ContainerUtils.addContainerEnvsToExistingEnvs(reconciliation, varList, templateContainer);

        return varList;
    }

    private List<ServicePort> getServicePortList() {
        List<ServicePort> portList = new ArrayList<>(4);
        portList.add(ServiceUtils.createServicePort(CLIENT_TLS_PORT_NAME, CLIENT_TLS_PORT, CLIENT_TLS_PORT, "TCP"));
        portList.add(ServiceUtils.createServicePort(CLUSTERING_PORT_NAME, CLUSTERING_PORT, CLUSTERING_PORT, "TCP"));
        portList.add(ServiceUtils.createServicePort(LEADER_ELECTION_PORT_NAME, LEADER_ELECTION_PORT, LEADER_ELECTION_PORT, "TCP"));

        portList.addAll(jmx.servicePorts());

        return portList;
    }

    private List<ContainerPort> getContainerPortList() {
        List<ContainerPort> portList = new ArrayList<>(4);

        portList.add(ContainerUtils.createContainerPort(CLUSTERING_PORT_NAME, CLUSTERING_PORT));
        portList.add(ContainerUtils.createContainerPort(LEADER_ELECTION_PORT_NAME, LEADER_ELECTION_PORT));
        portList.add(ContainerUtils.createContainerPort(CLIENT_TLS_PORT_NAME, CLIENT_TLS_PORT));

        if (metrics.isEnabled()) {
            portList.add(ContainerUtils.createContainerPort(MetricsModel.METRICS_PORT_NAME, MetricsModel.METRICS_PORT));
        }

        portList.addAll(jmx.containerPorts());

        return portList;
    }

    /**
     * Generates list of non-data volumes used by ZooKeeper Pods. This includes tmp volumes, mounted secrets and config
     * maps.
     *
     * @param isOpenShift   Indicates whether we are on OpenShift or not
     *
     * @return              List of nondata volumes used by the ZooKeeper pods
     */
    private List<Volume> getNonDataVolumes(boolean isOpenShift) {
        List<Volume> volumeList = new ArrayList<>(4);

        volumeList.add(VolumeUtils.createTempDirVolume(templatePod));
        volumeList.add(VolumeUtils.createConfigMapVolume(logAndMetricsConfigVolumeName, KafkaResources.zookeeperMetricsAndLogConfigMapName(cluster)));
        volumeList.add(VolumeUtils.createSecretVolume(ZOOKEEPER_NODE_CERTIFICATES_VOLUME_NAME, KafkaResources.zookeeperSecretName(cluster), isOpenShift));
        volumeList.add(VolumeUtils.createSecretVolume(ZOOKEEPER_CLUSTER_CA_VOLUME_NAME, AbstractModel.clusterCaCertSecretName(cluster), isOpenShift));

        return volumeList;
    }

    /**
     * Generates a list of volumes used by StatefulSet. For StatefulSet, it needs to include only ephemeral data
     * volumes. Persistent claim volumes are generated directly by StatefulSet.
     *
     * @param isOpenShift   Flag whether we are on OpenShift or not
     *
     * @return              List of volumes to be included in the StatefulSet pod template
     */
    private List<Volume> getStatefulSetVolumes(boolean isOpenShift) {
        List<Volume> volumeList = new ArrayList<>(5);

        volumeList.addAll(VolumeUtils.createStatefulSetVolumes(storage, false));
        volumeList.addAll(getNonDataVolumes(isOpenShift));

        return volumeList;
    }

    /**
     * Generates a list of volumes used by PodSets. For StrimziPodSet, it needs to include also all persistent claim
     * volumes which StatefulSet would generate on its own.
     *
     * @param podName       Name of the pod used to name the volumes
     * @param isOpenShift   Flag whether we are on OpenShift or not
     *
     * @return              List of volumes to be included in the StrimziPodSet pod
     */
    private List<Volume> getPodSetVolumes(String podName, boolean isOpenShift) {
        List<Volume> volumeList = new ArrayList<>(5);

        volumeList.addAll(VolumeUtils.createPodSetVolumes(podName, storage, false));
        volumeList.addAll(getNonDataVolumes(isOpenShift));

        return volumeList;
    }

    /**
     * Creates a list of Persistent Volume Claim templates for use in StatefulSets
     *
     * @return  List of Persistent Volume Claim Templates
     */
    /* test */ List<PersistentVolumeClaim> getPersistentVolumeClaimTemplates() {
        return VolumeUtils.createPersistentVolumeClaimTemplates(storage, false);
    }

    /**
     * @return  Generates list of ZooKeeper PVCs
     */
    public List<PersistentVolumeClaim> generatePersistentVolumeClaims() {
        return PersistentVolumeClaimUtils
                .createPersistentVolumeClaims(componentName, namespace, replicas, storage, false, labels, ownerReference, templatePersistentVolumeClaims, templateStatefulSet);
    }

    private List<VolumeMount> getVolumeMounts() {
        List<VolumeMount> volumeMountList = new ArrayList<>(5);

        volumeMountList.add(VolumeUtils.createTempDirVolumeMount());
        // ZooKeeper uses mount path which is different from the one used by Kafka.
        // As a result it cannot use VolumeUtils.getVolumeMounts and creates the volume mount directly
        volumeMountList.add(VolumeUtils.createVolumeMount(VOLUME_NAME, mountPath));
        volumeMountList.add(VolumeUtils.createVolumeMount(logAndMetricsConfigVolumeName, logAndMetricsConfigMountPath));
        volumeMountList.add(VolumeUtils.createVolumeMount(ZOOKEEPER_NODE_CERTIFICATES_VOLUME_NAME, ZOOKEEPER_NODE_CERTIFICATES_VOLUME_MOUNT));
        volumeMountList.add(VolumeUtils.createVolumeMount(ZOOKEEPER_CLUSTER_CA_VOLUME_NAME, ZOOKEEPER_CLUSTER_CA_VOLUME_MOUNT));

        return volumeMountList;
    }

    /**
     * Generates the PodDisruptionBudget.
     *
     * @param customController  Identifies whether the PDB should be generated for a custom controller (StrimziPodSets)
     *                          or not (Deployments, StatefulSet)
     *
     * @return The PodDisruptionBudget.
     */
    public PodDisruptionBudget generatePodDisruptionBudget(boolean customController) {
        if (customController) {
            return PodDisruptionBudgetUtils.createCustomControllerPodDisruptionBudget(componentName, namespace, labels, ownerReference, templatePodDisruptionBudget, replicas);
        } else {
            return PodDisruptionBudgetUtils.createPodDisruptionBudget(componentName, namespace, labels, ownerReference, templatePodDisruptionBudget);
        }
    }

    /**
     * Generates the PodDisruptionBudget V1Beta1.
     *
     * @param customController  Identifies whether the PDB should be generated for a custom controller (StrimziPodSets)
     *                          or not (Deployments, StatefulSet)
     *
     * @return The PodDisruptionBudget V1Beta1.
     */
    public io.fabric8.kubernetes.api.model.policy.v1beta1.PodDisruptionBudget generatePodDisruptionBudgetV1Beta1(boolean customController) {
        if (customController) {
            return PodDisruptionBudgetUtils.createCustomControllerPodDisruptionBudgetV1Beta1(componentName, namespace, labels, ownerReference, templatePodDisruptionBudget, replicas);
        } else {
            return PodDisruptionBudgetUtils.createPodDisruptionBudgetV1Beta1(componentName, namespace, labels, ownerReference, templatePodDisruptionBudget);
        }
    }

    /**
     * Generates a configuration ConfigMap with metrics and logging configurations and node count.
     *
     * @param metricsAndLogging    The ConfigMaps with original logging and metrics configurations.
     *
     * @return      The generated configuration ConfigMap.
     */
    public ConfigMap generateConfigurationConfigMap(MetricsAndLogging metricsAndLogging) {
        Map<String, String> data = MetricsAndLoggingUtils.generateMetricsAndLogConfigMapData(reconciliation, this, metricsAndLogging);
        data.put(CONFIG_MAP_KEY_ZOOKEEPER_NODE_COUNT, Integer.toString(getReplicas()));

        return ConfigMapUtils
                .createConfigMap(
                        KafkaResources.zookeeperMetricsAndLogConfigMapName(cluster),
                        namespace,
                        labels,
                        ownerReference,
                        data
                );
    }

    /**
     * @return  JMX Model instance for configuring JMX access
     */
    public JmxModel jmx()   {
        return jmx;
    }

    /**
     * @return  Metrics Model instance for configuring Prometheus metrics
     */
    public MetricsModel metrics()   {
        return metrics;
    }

    /**
     * @return  Logging Model instance for configuring logging
     */
    public LoggingModel logging()   {
        return logging;
    }
}
