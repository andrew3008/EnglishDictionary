package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Set;
import java.util.function.Supplier;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import lombok.experimental.UtilityClass;

@UtilityClass
final class AgentContractFailureMessages {

    private static final String MISSING = "<missing>";
    private static final String UNEXPECTED = "<unexpected>";

    static final String SUMMARY_TEMPLATE = "Platform tracing startup rejected: runtimeState=%s, reason=%s";

    static final String CONTROLLED_AGENT_MISSING_EXPLANATION = """
            The JVM was started without the Controlled Platform Agent. \
            Start the process with -javaagent pointing at the platform-tracing-javaagent distribution JAR.""";

    static final String PLATFORM_EXTENSION_MISSING_EXPLANATION = """
            The OpenTelemetry Java agent loaded, but the Platform Tracing readiness endpoint is absent. \
            Use the platform-tracing-javaagent distribution that embeds \
            extensions/platform-tracing-otel-javaagent-extension.jar.""";

    static final String DUAL_SDK_COLLISION_EXPLANATION = """
            An application-owned OpenTelemetry runtime was detected. \
            Remove application OpenTelemetry beans and GlobalOpenTelemetry setup.""";

    static final String CONTROLLED_AGENT_FORBIDDEN_EXPLANATION = """
            A Controlled Platform Agent is attached to this JVM, but platform.tracing.enabled=false \
            selects a mode that forbids it. platform.tracing.enabled=false disables the \
            platform tracing mode; it does not disable, detach, or authorize an attached \
            -javaagent. Remove -javaagent/agent injection from this deployment, or select \
            platform.tracing.enabled=true with a controlled-agent distribution compatible \
            with this starter.""";

    static final String READINESS_ENDPOINT_DISAPPEARED_EXPLANATION =
            "The readiness MBean disappeared after it was observed.";

    static final String READINESS_ACCESS_DENIED_EXPLANATION =
            "The application was denied access to the readiness MBean.";

    static final String READINESS_INVOCATION_FAILED_EXPLANATION =
            "Reading the readiness MBean failed. Inspect the Java-agent extension logs.";

    static final String READINESS_MBEAN_INFO_SHAPE_VIOLATION_EXPLANATION =
            "The readiness endpoint does not expose the exact generated OpenMBeanInfo contract.";

    static final String JMX_WIRE_TYPE_VIOLATION_EXPLANATION =
            "The readiness endpoint returned a value outside the generated neutral JMX wire allowlist.";

    static final String MALFORMED_READINESS_SNAPSHOT_EXPLANATION =
            "The readiness endpoint returned a malformed generated CompositeData snapshot.";

    static final String READINESS_MIXED_GENERATION_EXPLANATION =
            "The readiness anchor changed during double-collect; mixed runtime evidence was rejected.";

    static final String EXTENSION_READINESS_TIMEOUT_EXPLANATION = """
            The extension stayed in INITIALIZING until the startup readiness timeout elapsed. \
            OpenTelemetry SDK autoconfiguration may have been aborted after platform preparation \
            (for example, an invalid exporter endpoint); check the agent startup log \
            for a ConfigurationException.""";

    static final String READINESS_SCHEMA_MISMATCH_EXPLANATION =
            "The readiness schema identity does not equal the generated application contract.";

    static final String READINESS_MISSING_REQUIRED_MISMATCH_EXPLANATION =
            "Producer missingRequired differs from the consumer recomputation.";

    static final String REQUIRED_CAPABILITY_MISSING_TEMPLATE =
            "At least one generated required capability is absent. missingRequired=%s.";

    static final String READINESS_GENERATION_INVALID_EXPLANATION =
            "agentInstanceId, evidenceGeneration, and batchId are not coherent.";

    static final String DISTRIBUTION_EVIDENCE_INVALID_EXPLANATION =
            "Controlled-agent distribution identity evidence is absent or invalid.";

    static final String SPI_ATTESTATION_EVIDENCE_INVALID_EXPLANATION =
            "Trace-pipeline SPI attestation evidence is absent, malformed, or does not match its pin.";

    static final String CLASSLOADER_TOPOLOGY_INVALID_EXPLANATION =
            "Classloader topology evidence is absent or inconsistent.";

    static final String EXTENSION_FAILED_WITHOUT_CODE_EXPLANATION =
            "The extension reported lifecycle=FAILED without a failureCode.";

    static final String EXTENSION_FAILED_CODE_PAIR_INCONSISTENT_EXPLANATION =
            "The extension reported an inconsistent primary and effective failure-code pair.";

    static final String EXTENSION_INITIALIZING_INCONSISTENT_EXPLANATION =
            "The extension reported an inconsistent INITIALIZING envelope.";

    static final String EXTENSION_LIFECYCLE_UNEXPECTED_EXPLANATION =
            "The extension lifecycle is not READY, INITIALIZING, or FAILED.";

    static final String EXTENSION_READY_WITH_FAILURE_CODE_EXPLANATION =
            "The extension reported READY with a non-blank failure code.";

    static final String SHUTDOWN_CLEANUP_FAILED_EXPLANATION = """
            The Java-agent extension could not release one or more runtime resources during JVM shutdown. \
            Inspect the structured agent log entry with phase=close for the failed resource and cause.""";

    static final String UNSPECIFIED_CONTRACT_FAILURE_TEMPLATE =
            "Observed runtimeState=%s is not allowed for platform.tracing.enabled=%s.";

    static final String DEFAULT_EXPLANATION_TEMPLATE =
            "The Java-agent extension reported this failure under lifecycle=%s.";

    static final String VERSION_MISMATCH_RELEASE_DETAIL =
            "Use the tracing starter and controlled Java agent distribution from the same platform release.";

    static String reject(boolean tracingEnabled,
                         TracingDeploymentDiagnostics diagnostics,
                         Supplier<String> applicationVersion) {
        TracingDeploymentDiagnostics.AgentReadiness readiness = diagnostics.agentReadiness();
        String reason = resolveReason(readiness.failureCode());

        String summary = SUMMARY_TEMPLATE.formatted(diagnostics.runtimeState(), reason);
        String context = readiness instanceof TracingDeploymentDiagnostics.Observed observed
                ? primaryFailureContext(observed) + context(reason, observed, applicationVersion)
                : "";
        String explanation = explanation(reason, tracingEnabled, diagnostics.runtimeState(), readiness);
        return summary + context + ". " + explanation;
    }

    private static String resolveReason(String failureCode) {
        return StringUtils.hasText(failureCode) ? failureCode : "UNSPECIFIED_CONTRACT_FAILURE";
    }

    private static String primaryFailureContext(TracingDeploymentDiagnostics.Observed observed) {
        String primaryFailureCode = observed.primaryFailureCode();
        if (!StringUtils.hasText(primaryFailureCode) || primaryFailureCode.equals(observed.failureCode())) {
            return "";
        }

        return join(requiredField("primaryFailureCode", primaryFailureCode));
    }

    private static String context(String reason,
                                  TracingDeploymentDiagnostics.Observed observed,
                                  Supplier<String> applicationVersion) {
        return switch (reason) {
            case AgentNotReadyReasonCodes.EXTENSION_VERSION_MISMATCH -> versionContext(applicationVersion.get(), observed);

            case AgentNotReadyReasonCodes.READINESS_SCHEMA_ID_MISMATCH -> schemaIdContext(observed);

            case AgentNotReadyReasonCodes.READINESS_SCHEMA_DIGEST_MISMATCH -> schemaDigestContext(observed);

            case AgentNotReadyReasonCodes.READINESS_GENERATION_INVALID -> generationInvalidContext(observed);

            case AgentNotReadyReasonCodes.READINESS_GENERATION_INCONSISTENT -> generationInconsistentContext(observed);

            case "READINESS_ANCHOR_CONTENT_DRIFT",
                 "READINESS_PINNED_ANCHOR_CHANGED",
                 "READINESS_ANCHOR_NOT_PINNED" -> requiredAnchorContext(observed);

            case AgentNotReadyReasonCodes.EXTENSION_INITIALIZING_INCONSISTENT -> initializingInconsistentContext(observed);

            case "DISTRIBUTION_MISMATCH",
                 AgentNotReadyReasonCodes.DISTRIBUTION_EVIDENCE_INVALID,
                 "DISTRIBUTION_EVIDENCE_MISSING" -> distributionContext(observed);

            case AgentNotReadyReasonCodes.CLASSLOADER_TOPOLOGY_INVALID,
                 AgentNotReadyReasonCodes.EXTENSION_LINKAGE_ERROR -> classloaderContext(observed);

            case "CONTROLLED_AGENT_MISSING",
                 "PLATFORM_EXTENSION_MISSING",
                 AgentNotReadyReasonCodes.DUAL_SDK_COLLISION,
                 "CONTROLLED_AGENT_FORBIDDEN",
                 "READINESS_ENDPOINT_DISAPPEARED",
                 "READINESS_ACCESS_DENIED",
                 "READINESS_INVOCATION_FAILED",
                 "READINESS_MBEAN_INFO_SHAPE_VIOLATION",
                 "JMX_WIRE_TYPE_VIOLATION",
                 "MALFORMED_READINESS_SNAPSHOT",
                 "READINESS_MIXED_GENERATION",
                 AgentNotReadyReasonCodes.REQUIRED_CAPABILITY_MISSING,
                 AgentNotReadyReasonCodes.READINESS_MISSING_REQUIRED_MISMATCH,
                 "UNSPECIFIED_CONTRACT_FAILURE" -> "";

            default -> lifecycleContext(observed);
        };
    }

    private static String versionContext(String applicationVersion,
                                         TracingDeploymentDiagnostics.Observed observed) {
        return join(
                requiredField("applicationVersion", applicationVersion),
                requiredField("extensionVersion", observed.snapshot().distributionIdentity().extensionVersion())
        );
    }

    private static String schemaIdContext(TracingDeploymentDiagnostics.Observed observed) {
        return join(
                optionalField("expectedContractId", JmxWireContract.SCHEMA_ID),
                requiredField("observedContractId", observed.snapshot().contractIdentity().contractId())
        );
    }

    private static String schemaDigestContext(TracingDeploymentDiagnostics.Observed observed) {
        return join(
                optionalField("expectedSchemaDigest", JmxWireContract.SCHEMA_DIGEST_SHA256),
                requiredField("observedSchemaDigest", observed.snapshot().contractIdentity().schemaDigest())
        );
    }

    private static String generationInvalidContext(TracingDeploymentDiagnostics.Observed observed) {
        return join(requiredGeneration(observed.snapshot().anchorIdentity().evidenceGeneration()));
    }

    private static String generationInconsistentContext(TracingDeploymentDiagnostics.Observed observed) {
        AgentReadinessSnapshot.AnchorIdentity anchor = observed.snapshot().anchorIdentity();
        return join(
                missingOnlyField("agentInstanceId", anchor.agentInstanceId()),
                requiredGeneration(anchor.evidenceGeneration()),
                missingOnlyField("batchId", anchor.batchId())
        );
    }

    private static String requiredAnchorContext(TracingDeploymentDiagnostics.Observed observed) {
        AgentReadinessSnapshot.AnchorIdentity anchor = observed.snapshot().anchorIdentity();
        return join(
                requiredField("agentInstanceId", anchor.agentInstanceId()),
                requiredGeneration(anchor.evidenceGeneration()),
                requiredField("batchId", anchor.batchId())
        );
    }

    private static String initializingInconsistentContext(TracingDeploymentDiagnostics.Observed observed) {
        AgentReadinessSnapshot snapshot = observed.snapshot();
        return join(
                requiredField("lifecycle", snapshot.lifecycleState().lifecycle()),
                requiredGeneration(snapshot.anchorIdentity().evidenceGeneration()),
                unexpectedField("batchId", snapshot.anchorIdentity().batchId())
        );
    }

    private static String lifecycleContext(TracingDeploymentDiagnostics.Observed observed) {
        return join(requiredField("lifecycle", observed.snapshot().lifecycleState().lifecycle()));
    }

    private static String distributionContext(TracingDeploymentDiagnostics.Observed observed) {
        AgentReadinessSnapshot snapshot = observed.snapshot();
        AgentReadinessSnapshot.DistributionIdentity distribution = snapshot.distributionIdentity();
        return join(
                requiredField("extensionVersion", distribution.extensionVersion()),
                requiredField("upstreamAgentVersion", distribution.upstreamAgentVersion()),
                requiredField("lifecycle", snapshot.lifecycleState().lifecycle())
        );
    }

    private static String classloaderContext(TracingDeploymentDiagnostics.Observed observed) {
        AgentReadinessSnapshot snapshot = observed.snapshot();
        AgentReadinessSnapshot.ClassloaderTopology topology = snapshot.classloaderTopology();
        return join(
                requiredField("extensionVersion", snapshot.distributionIdentity().extensionVersion()),
                requiredField("extensionDefiningLoader", topology.extensionDefiningLoader()),
                requiredField("sdkDefiningLoader", topology.sdkDefiningLoader()),
                requiredField("lifecycle", snapshot.lifecycleState().lifecycle())
        );
    }

    private static String explanation(String reason,
                                      boolean tracingEnabled,
                                      AgentRuntimeState runtimeState,
                                      TracingDeploymentDiagnostics.AgentReadiness readiness) {
        return switch (reason) {
            case "CONTROLLED_AGENT_MISSING" -> CONTROLLED_AGENT_MISSING_EXPLANATION;

            case "PLATFORM_EXTENSION_MISSING" -> PLATFORM_EXTENSION_MISSING_EXPLANATION;

            case AgentNotReadyReasonCodes.DUAL_SDK_COLLISION -> DUAL_SDK_COLLISION_EXPLANATION;

            case "CONTROLLED_AGENT_FORBIDDEN" -> CONTROLLED_AGENT_FORBIDDEN_EXPLANATION;

            case "READINESS_ENDPOINT_DISAPPEARED" -> READINESS_ENDPOINT_DISAPPEARED_EXPLANATION;

            case "READINESS_ACCESS_DENIED" -> READINESS_ACCESS_DENIED_EXPLANATION;

            case "READINESS_INVOCATION_FAILED" -> READINESS_INVOCATION_FAILED_EXPLANATION;

            case "READINESS_MBEAN_INFO_SHAPE_VIOLATION" -> READINESS_MBEAN_INFO_SHAPE_VIOLATION_EXPLANATION;

            case "JMX_WIRE_TYPE_VIOLATION" -> JMX_WIRE_TYPE_VIOLATION_EXPLANATION;

            case "MALFORMED_READINESS_SNAPSHOT" -> MALFORMED_READINESS_SNAPSHOT_EXPLANATION;

            case "READINESS_MIXED_GENERATION" -> READINESS_MIXED_GENERATION_EXPLANATION;

            case "EXTENSION_READINESS_TIMEOUT" -> EXTENSION_READINESS_TIMEOUT_EXPLANATION;

            case AgentNotReadyReasonCodes.EXTENSION_VERSION_MISMATCH -> VERSION_MISMATCH_RELEASE_DETAIL;

            case AgentNotReadyReasonCodes.READINESS_SCHEMA_ID_MISMATCH,
                 AgentNotReadyReasonCodes.READINESS_SCHEMA_DIGEST_MISMATCH -> READINESS_SCHEMA_MISMATCH_EXPLANATION;

            case AgentNotReadyReasonCodes.READINESS_MISSING_REQUIRED_MISMATCH -> READINESS_MISSING_REQUIRED_MISMATCH_EXPLANATION;

            case AgentNotReadyReasonCodes.REQUIRED_CAPABILITY_MISSING ->
                    REQUIRED_CAPABILITY_MISSING_TEMPLATE.formatted(observedMissingRequired(readiness));

            case AgentNotReadyReasonCodes.READINESS_GENERATION_INVALID,
                 AgentNotReadyReasonCodes.READINESS_GENERATION_INCONSISTENT -> READINESS_GENERATION_INVALID_EXPLANATION;

            case AgentNotReadyReasonCodes.DISTRIBUTION_EVIDENCE_INVALID,
                 "DISTRIBUTION_MISMATCH",
                 "DISTRIBUTION_EVIDENCE_MISSING" -> DISTRIBUTION_EVIDENCE_INVALID_EXPLANATION;

            case "SPI_ATTESTATION_EVIDENCE_INVALID", "SPI_DISCOVERY_INCOMPLETE" -> SPI_ATTESTATION_EVIDENCE_INVALID_EXPLANATION;

            case AgentNotReadyReasonCodes.CLASSLOADER_TOPOLOGY_INVALID,
                 AgentNotReadyReasonCodes.EXTENSION_LINKAGE_ERROR -> CLASSLOADER_TOPOLOGY_INVALID_EXPLANATION;

            case AgentNotReadyReasonCodes.EXTENSION_FAILED_WITHOUT_CODE -> EXTENSION_FAILED_WITHOUT_CODE_EXPLANATION;

            case AgentNotReadyReasonCodes.EXTENSION_FAILED_CODE_PAIR_INCONSISTENT ->
                    EXTENSION_FAILED_CODE_PAIR_INCONSISTENT_EXPLANATION;

            case AgentNotReadyReasonCodes.EXTENSION_INITIALIZING_INCONSISTENT -> EXTENSION_INITIALIZING_INCONSISTENT_EXPLANATION;

            case AgentNotReadyReasonCodes.EXTENSION_LIFECYCLE_UNEXPECTED -> EXTENSION_LIFECYCLE_UNEXPECTED_EXPLANATION;

            case AgentNotReadyReasonCodes.EXTENSION_READY_WITH_FAILURE_CODE -> EXTENSION_READY_WITH_FAILURE_CODE_EXPLANATION;

            case "SHUTDOWN_CLEANUP_FAILED" -> SHUTDOWN_CLEANUP_FAILED_EXPLANATION;

            case "UNSPECIFIED_CONTRACT_FAILURE" -> UNSPECIFIED_CONTRACT_FAILURE_TEMPLATE.formatted(runtimeState, tracingEnabled);

            default -> DEFAULT_EXPLANATION_TEMPLATE.formatted(display(observedLifecycle(readiness)));
        };
    }

    private static String observedLifecycle(TracingDeploymentDiagnostics.AgentReadiness readiness) {
        return readiness instanceof TracingDeploymentDiagnostics.Observed observed
                ? observed.snapshot().lifecycleState().lifecycle()
                : "";
    }

    private static Set<String> observedMissingRequired(TracingDeploymentDiagnostics.AgentReadiness readiness) {
        return readiness instanceof TracingDeploymentDiagnostics.Observed observed
                ? observed.snapshot().lifecycleState().missingRequired()
                : Set.of();
    }

    private static String requiredField(String name, String value) {
        return name + "=" + (StringUtils.hasText(value) ? value : MISSING);
    }

    private static String optionalField(String name, String value) {
        return StringUtils.hasText(value) ? name + "=" + value : "";
    }

    private static String missingOnlyField(String name, String value) {
        return StringUtils.hasText(value) ? "" : name + "=" + MISSING;
    }

    @SuppressWarnings("SameParameterValue")
    private static String unexpectedField(String name, String value) {
        return StringUtils.hasText(value) ? name + "=" + UNEXPECTED : "";
    }

    private static String requiredGeneration(long evidenceGeneration) {
        return "evidenceGeneration=" + evidenceGeneration;
    }

    private static String join(String... fields) {
        StringBuilder builder = new StringBuilder();
        for (String field : fields) {
            if (!StringUtils.hasText(field)) {
                continue;
            }

            builder.append(", ").append(field);
        }

        return builder.toString();
    }

    private static String display(String value) {
        return StringUtils.hasText(value) ? value : "unresolved";
    }
}
