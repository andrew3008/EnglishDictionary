package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

/** Предварительно собранный coherent readiness envelope для одной runtime epoch. */
public final class ExtensionReadinessWireSnapshot {

    private static final String INITIALIZING = PlatformExtensionLifecycle.INITIALIZING.name();
    private static final String READY = PlatformExtensionLifecycle.READY.name();
    private static final String FAILED = PlatformExtensionLifecycle.FAILED.name();
    private static final Pattern FAILURE_CODE_PATTERN =
            Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

    private final String runtimeEpoch;
    private final long evidenceGeneration;
    private final String batchId;
    private final String lifecycle;
    private final List<String> capabilities;
    private final List<String> missingRequired;
    private final String failureCode;
    private final ExtensionRuntimeEvidence evidence;
    private final CompositeData readinessSnapshot;

    private ExtensionReadinessWireSnapshot(String runtimeEpoch,
                                           long evidenceGeneration,
                                           String batchId,
                                           String lifecycle,
                                           String[] capabilities,
                                           String[] missingRequired,
                                           String failureCode,
                                           ExtensionRuntimeEvidence evidence) {
        this.runtimeEpoch = Objects.requireNonNull(runtimeEpoch, "runtimeEpoch");
        this.evidenceGeneration = evidenceGeneration;
        this.batchId = Objects.requireNonNull(batchId, "batchId");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.capabilities = List.of(Objects.requireNonNull(capabilities, "capabilities"));
        this.missingRequired = List.of(Objects.requireNonNull(missingRequired, "missingRequired"));
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        if (evidenceGeneration < 0L) {
            throw new IllegalArgumentException("evidenceGeneration must not be negative");
        }
        this.readinessSnapshot = buildCompositeData();
    }

    public static ExtensionReadinessWireSnapshot initializing() {
        return new ExtensionReadinessWireSnapshot(
                "",
                0L,
                "",
                INITIALIZING,
                new String[0],
                requiredCapabilities(),
                "",
                ExtensionRuntimeEvidence.unavailable("development")
        );
    }

    public static ExtensionReadinessWireSnapshot initializing(PlatformExtensionReadiness readiness) {
        return initializing(readiness, "");
    }

    public static ExtensionReadinessWireSnapshot initializing(PlatformExtensionReadiness readiness,
                                                              String runtimeEpoch) {
        Objects.requireNonNull(readiness, "readiness");
        return new ExtensionReadinessWireSnapshot(
                Objects.requireNonNull(runtimeEpoch, "runtimeEpoch"),
                0L,
                "",
                INITIALIZING,
                readiness.capabilities(),
                missingRequired(readiness),
                "",
                ExtensionRuntimeEvidence.unavailable(readiness.extensionVersion())
        );
    }

    public static ExtensionReadinessWireSnapshot ready(PlatformExtensionReadiness readiness,
                                                       String runtimeEpoch,
                                                       String batchId,
                                                       long evidenceGeneration,
                                                       ExtensionRuntimeEvidence evidence) {
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(evidence, "evidence");
        if (readiness.lifecycle() != PlatformExtensionLifecycle.READY) {
            throw new IllegalStateException(
                    "Cannot construct a READY wire snapshot: underlying lifecycle=" + readiness.lifecycle()
            );
        }

        String[] missingRequired = missingRequired(readiness);
        if (missingRequired.length != 0) {
            throw new IllegalStateException(
                    "Cannot construct a READY wire snapshot with missing required capabilities"
            );
        }
        if (runtimeEpoch == null || runtimeEpoch.isBlank()) {
            throw new IllegalArgumentException("READY runtimeEpoch must not be blank");
        }
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("READY batchId must not be blank");
        }
        if (evidenceGeneration <= 0L) {
            throw new IllegalArgumentException("READY evidenceGeneration must be positive");
        }
        if (!evidence.complete()) {
            throw new IllegalStateException("Cannot construct a READY wire snapshot with incomplete evidence");
        }
        if (!readiness.extensionVersion().equals(evidence.extensionVersion())) {
            throw new IllegalStateException("Readiness and attested extension versions do not match");
        }
        return new ExtensionReadinessWireSnapshot(
                runtimeEpoch,
                evidenceGeneration,
                batchId,
                READY,
                readiness.capabilities(),
                missingRequired,
                "",
                evidence
        );
    }

    public static ExtensionReadinessWireSnapshot failed(PlatformExtensionReadiness readiness,
                                                        String runtimeEpoch,
                                                        String batchId,
                                                        long evidenceGeneration,
                                                        String failureCode,
                                                        ExtensionRuntimeEvidence evidence) {
        Objects.requireNonNull(readiness, "readiness");
        String effectiveFailureCode = requireFailureCode(failureCode);
        return new ExtensionReadinessWireSnapshot(
                (runtimeEpoch == null) ? "" : runtimeEpoch,
                evidenceGeneration,
                (batchId == null) ? "" : batchId,
                FAILED,
                readiness.capabilities(),
                missingRequired(readiness),
                effectiveFailureCode,
                Objects.requireNonNull(evidence, "evidence")
        );
    }

    public CompositeData readinessSnapshot() {
        return readinessSnapshot;
    }

    public String runtimeEpoch() {
        return runtimeEpoch;
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

    public ExtensionRuntimeEvidence evidence() {
        return evidence;
    }

    private CompositeData buildCompositeData() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_CONTRACT_ID,
                JmxWireContract.SCHEMA_ID);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_SCHEMA_DIGEST,
                JmxWireContract.SCHEMA_DIGEST_SHA256);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_RUNTIME_EPOCH, runtimeEpoch);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_EVIDENCE_GENERATION,
                evidenceGeneration);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_BATCH_ID, batchId);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE, lifecycle);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES,
                capabilities.toArray(String[]::new));
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_MISSING_REQUIRED,
                missingRequired.toArray(String[]::new));
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE, failureCode);
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_DISTRIBUTION_SHA256,
                evidence.agentDistributionSha256());
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_UPSTREAM_AGENT_VERSION,
                evidence.upstreamAgentVersion());
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_VERSION,
                evidence.extensionVersion());
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_DEFINING_LOADER,
                evidence.extensionDefiningLoader());
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_SDK_DEFINING_LOADER,
                evidence.sdkDefiningLoader());
        try {
            return new CompositeDataSupport(JmxOpenTypes.READINESS_SNAPSHOT, values);
        } catch (OpenDataException exception) {
            throw new IllegalStateException("Unable to construct the readiness OpenData snapshot", exception);
        }
    }

    private static String[] missingRequired(PlatformExtensionReadiness readiness) {
        Set<String> installed = Set.of(readiness.capabilities());
        return JmxWireContract.REQUIRED_CAPABILITIES.stream()
                .filter(capability -> !installed.contains(capability))
                .sorted()
                .toArray(String[]::new);
    }

    private static String[] requiredCapabilities() {
        return JmxWireContract.REQUIRED_CAPABILITIES.stream()
                .sorted()
                .toArray(String[]::new);
    }

    private static String requireFailureCode(String failureCode) {
        if (failureCode == null || !FAILURE_CODE_PATTERN.matcher(failureCode).matches()) {
            throw new IllegalArgumentException(
                    "FAILED failureCode must match [A-Z][A-Z0-9_]{0,127}");
        }
        return failureCode;
    }
}
