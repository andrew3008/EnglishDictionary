package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;
import space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessFailure;

public final class ExtensionReadinessWireSnapshot {

    private static final String NO_AGENT_INSTANCE_ID = "";
    private static final String NO_BATCH_ID = "";
    private static final String NO_EXTENSION_VERSION = "";
    private static final long NO_EVIDENCE_GENERATION = 0L;
    private final String agentInstanceId;
    private final long evidenceGeneration;
    private final String batchId;
    private final String lifecycle;
    private final List<String> capabilities;
    private final List<String> missingRequired;
    private final String failureCode;
    private final String primaryFailureCode;
    private final ExtensionRuntimeEvidence evidence;
    private final CompositeData readinessSnapshot;

    private ExtensionReadinessWireSnapshot(String agentInstanceId,
                                           long evidenceGeneration,
                                           String batchId,
                                           PlatformExtensionLifecycle lifecycle,
                                           Capabilities capabilities,
                                           FailureCodes failureCodes,
                                           ExtensionRuntimeEvidence evidence) {
        if (evidenceGeneration < 0L) {
            throw new IllegalArgumentException("evidenceGeneration must not be negative");
        }

        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
        this.evidenceGeneration = evidenceGeneration;
        this.batchId = Objects.requireNonNull(batchId, "batchId");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle").name();
        this.capabilities = capabilities.installed();
        this.missingRequired = capabilities.missingRequired();
        this.failureCode = failureCodes.effective();
        this.primaryFailureCode = failureCodes.primary();
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.readinessSnapshot = buildCompositeData();
    }

    public static ExtensionReadinessWireSnapshot initializing() {
        return new ExtensionReadinessWireSnapshot(
                NO_AGENT_INSTANCE_ID,
                NO_EVIDENCE_GENERATION,
                NO_BATCH_ID,
                PlatformExtensionLifecycle.INITIALIZING,
                Capabilities.noneInstalled(),
                FailureCodes.NONE,
                ExtensionRuntimeEvidence.unavailable(NO_EXTENSION_VERSION)
        );
    }

    public static ExtensionReadinessWireSnapshot initializing(PlatformExtensionReadinessSnapshot.Initializing readiness,
                                                              String agentInstanceId) {
        Objects.requireNonNull(readiness, "readiness");
        return new ExtensionReadinessWireSnapshot(
                requireAgentInstanceId(agentInstanceId),
                NO_EVIDENCE_GENERATION,
                NO_BATCH_ID,
                PlatformExtensionLifecycle.INITIALIZING,
                Capabilities.of(readiness),
                FailureCodes.NONE,
                ExtensionRuntimeEvidence.unavailable(readiness.extensionVersion())
        );
    }

    public static ExtensionReadinessWireSnapshot ready(PlatformExtensionReadinessSnapshot.Ready readiness,
                                                       String agentInstanceId,
                                                       String batchId,
                                                       long evidenceGeneration,
                                                       ExtensionRuntimeEvidence evidence) {
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(evidence, "evidence");

        if (StringUtils.isNullOrBlank(batchId)) {
            throw new IllegalArgumentException("READY batchId must not be blank");
        }

        if (evidenceGeneration <= 0L) {
            throw new IllegalArgumentException("READY evidenceGeneration must be positive");
        }

        if (!evidence.complete()) {
            throw new IllegalStateException("Cannot construct a READY wire snapshot with incomplete evidence");
        }

        requireMatchingExtensionVersion(readiness, evidence);
        return new ExtensionReadinessWireSnapshot(
                requireAgentInstanceId(agentInstanceId),
                evidenceGeneration,
                batchId,
                PlatformExtensionLifecycle.READY,
                Capabilities.of(readiness),
                FailureCodes.NONE,
                evidence
        );
    }

    public static ExtensionReadinessWireSnapshot failed(PlatformExtensionReadinessSnapshot.Failed readiness,
                                                        String agentInstanceId,
                                                        String batchId,
                                                        long evidenceGeneration,
                                                        ExtensionRuntimeEvidence evidence) {
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(evidence, "evidence");
        requireMatchingExtensionVersion(readiness, evidence);

        return new ExtensionReadinessWireSnapshot(
                requireAgentInstanceId(agentInstanceId),
                evidenceGeneration,
                Objects.requireNonNullElse(batchId, NO_BATCH_ID),
                PlatformExtensionLifecycle.FAILED,
                Capabilities.of(readiness),
                FailureCodes.of(readiness.failure()),
                evidence
        );
    }

    public CompositeData readinessSnapshot() {
        return readinessSnapshot;
    }

    public String agentInstanceId() {
        return agentInstanceId;
    }

    public long evidenceGeneration() {
        return evidenceGeneration;
    }

    public String batchId() {
        return batchId;
    }

    public String lifecycle() {
        return lifecycle;
    }

    public List<String> capabilities() {
        return capabilities;
    }

    public List<String> missingRequired() {
        return missingRequired;
    }

    public String failureCode() {
        return failureCode;
    }

    public String primaryFailureCode() {
        return primaryFailureCode;
    }

    public ExtensionRuntimeEvidence evidence() {
        return evidence;
    }

    private CompositeData buildCompositeData() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_CONTRACT_ID, JmxWireContract.SCHEMA_ID);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_SCHEMA_DIGEST, JmxWireContract.SCHEMA_DIGEST_SHA256);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID, agentInstanceId);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_EVIDENCE_GENERATION, evidenceGeneration);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_BATCH_ID, batchId);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE, lifecycle);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES, capabilities.toArray(String[]::new));
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_MISSING_REQUIRED, missingRequired.toArray(String[]::new));
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE, failureCode);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_PRIMARY_FAILURE_CODE, primaryFailureCode);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_DISTRIBUTION_SHA256, evidence.agentDistributionSha256());
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_UPSTREAM_AGENT_VERSION, evidence.upstreamAgentVersion());
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_VERSION, evidence.extensionVersion());
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_DEFINING_LOADER, evidence.extensionDefiningLoader());
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_SDK_DEFINING_LOADER, evidence.sdkDefiningLoader());

        try {
            return new CompositeDataSupport(JmxOpenTypes.READINESS_SNAPSHOT, values);
        } catch (OpenDataException exception) {
            throw new IllegalStateException("Unable to construct the readiness OpenData snapshot", exception);
        }
    }

    private static String requireAgentInstanceId(String agentInstanceId) {
        if (StringUtils.isNullOrBlank(agentInstanceId)) {
            throw new IllegalArgumentException("agentInstanceId must not be blank");
        }

        return AgentInstanceId.parse(agentInstanceId).toString();
    }

    private static void requireMatchingExtensionVersion(PlatformExtensionReadinessSnapshot readiness,
                                                        ExtensionRuntimeEvidence evidence) {
        if (!readiness.extensionVersion().equals(evidence.extensionVersion())) {
            throw new IllegalStateException("Readiness and attested extension versions do not match");
        }
    }

    private record Capabilities(List<String> installed, List<String> missingRequired) {

        static Capabilities of(PlatformExtensionReadinessSnapshot readiness) {
            return new Capabilities(
                    names(readiness.installedCapabilities()),
                    names(readiness.missingRequiredCapabilities())
            );
        }

        static Capabilities noneInstalled() {
            return new Capabilities(
                    List.of(),
                    JmxWireContract.REQUIRED_CAPABILITIES.stream().sorted().toList()
            );
        }

        private static List<String> names(Set<PlatformExtensionCapability> capabilities) {
            return capabilities.stream()
                    .map(Enum::name)
                    .sorted()
                    .toList();
        }
    }

    private record FailureCodes(String primary, String effective) {

        static final FailureCodes NONE = new FailureCodes("", "");

        static FailureCodes of(ReadinessFailure failure) {
            return new FailureCodes(failure.primaryCode(), failure.effectiveCode());
        }
    }
}
