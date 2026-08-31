package space.br1440.platform.tracing.autoconfigure.agent;

import space.br1440.platform.common.util.StringUtils;

final class AgentContractFailureMessages {

    static final String EXPECTED_EXTENSION_IMPLEMENTATION_TITLE = "platform-tracing-otel-javaagent-extension";

    private AgentContractFailureMessages() {
    }

    static String reject(boolean tracingEnabled,
                         TracingDeploymentDiagnostics diagnostics,
                         String applicationVersion) {
        TracingDeploymentDiagnostics.AgentReadiness readiness = diagnostics.agentReadiness();
        TracingDeploymentDiagnostics.LifecycleState lifecycleState = readiness.lifecycleState();
        TracingDeploymentDiagnostics.ContractIdentity contractIdentity = readiness.contractIdentity();
        TracingDeploymentDiagnostics.ReadinessAnchor readinessAnchor = readiness.readinessAnchor();
        TracingDeploymentDiagnostics.DistributionIdentity distributionIdentity =
                readiness.distributionIdentity();
        String reason = StringUtils.hasText(lifecycleState.failureCode())
                ? lifecycleState.failureCode()
                : "UNSPECIFIED_CONTRACT_FAILURE";

        StringBuilder message = new StringBuilder();
        message.append("platform.tracing.enabled=").append(tracingEnabled)
                .append(" rejected Controlled Platform Agent: runtimeState=")
                .append(diagnostics.runtimeState())
                .append(", reason=").append(reason);

        if (readiness.endpointPresent() && StringUtils.hasText(contractIdentity.contractId())) {
            message.append(", applicationVersion=").append(display(applicationVersion))
                    .append(", extensionVersion=").append(display(distributionIdentity.extensionVersion()))
                    .append(", contractId=").append(display(contractIdentity.contractId()))
                    .append(", runtimeEpoch=").append(display(readinessAnchor.runtimeEpoch()))
                    .append(", evidenceGeneration=").append(readinessAnchor.evidenceGeneration())
                    .append(", batchId=").append(display(readinessAnchor.batchId()))
                    .append(", lifecycle=").append(display(lifecycleState.lifecycle()));
        }

        message.append(". ").append(explanation(reason, tracingEnabled, diagnostics.runtimeState(), readiness));
        return message.toString();
    }

    private static String explanation(String reason,
                                      boolean tracingEnabled,
                                      AgentRuntimeState runtimeState,
                                      TracingDeploymentDiagnostics.AgentReadiness readiness) {
        return switch (reason) {
            case "CONTROLLED_AGENT_MISSING" ->
                    "The JVM was started without the Controlled Platform Agent. "
                            + "Start the process with -javaagent pointing at the platform-tracing-javaagent distribution JAR.";
            case "PLATFORM_EXTENSION_MISSING" ->
                    "The OpenTelemetry Java agent loaded, but the Platform Tracing readiness endpoint is absent. "
                            + "Use the platform-tracing-javaagent distribution that embeds "
                            + "extensions/platform-tracing-otel-javaagent-extension.jar.";
            case "DUAL_SDK_COLLISION" ->
                    "An application-owned OpenTelemetry runtime was detected. "
                            + "Remove application OpenTelemetry beans and GlobalOpenTelemetry setup.";
            case "CONTROLLED_AGENT_FORBIDDEN" ->
                    "platform.tracing.enabled=false forbids a loaded Controlled Platform Agent. "
                            + "Remove -javaagent or set platform.tracing.enabled=true.";
            case "READINESS_ENDPOINT_DISAPPEARED" ->
                    "The readiness MBean disappeared after it was observed.";
            case "READINESS_ACCESS_DENIED" ->
                    "The application was denied access to the readiness MBean.";
            case "READINESS_INVOCATION_FAILED" ->
                    "Reading the readiness MBean failed. Inspect the Java-agent extension logs.";
            case "READINESS_MBEAN_INFO_SHAPE_VIOLATION" ->
                    "The readiness endpoint does not expose the exact generated OpenMBeanInfo contract.";
            case "JMX_WIRE_TYPE_VIOLATION" ->
                    "The readiness endpoint returned a value outside the generated neutral JMX wire allowlist.";
            case "MALFORMED_READINESS_SNAPSHOT" ->
                    "The readiness endpoint returned a malformed generated CompositeData snapshot.";
            case "READINESS_MIXED_GENERATION" ->
                    "The readiness anchor changed during double-collect; mixed runtime evidence was rejected.";
            case "EXTENSION_READINESS_TIMEOUT" ->
                    "The extension stayed in INITIALIZING until the startup readiness timeout elapsed.";
            case "EXTENSION_VERSION_MISMATCH" ->
                    versionMismatchExplanation(readiness.distributionIdentity().extensionVersion());
            case "DEVELOPMENT_VERSION_FORBIDDEN" ->
                    "A development extension version is forbidden by the production readiness contract.";
            case "READINESS_SCHEMA_ID_MISMATCH", "READINESS_SCHEMA_DIGEST_MISMATCH" ->
                    "The readiness schema identity does not equal the generated application contract.";
            case "READINESS_MISSING_REQUIRED_MISMATCH" ->
                    "Producer missingRequired differs from the consumer recomputation.";
            case "REQUIRED_CAPABILITY_MISSING" ->
                    "At least one generated required capability is absent. missingRequired="
                            + readiness.lifecycleState().missingRequired() + ".";
            case "READINESS_GENERATION_INVALID", "READINESS_GENERATION_INCONSISTENT" ->
                    "runtimeEpoch, evidenceGeneration, and batchId are not coherent.";
            case "DISTRIBUTION_EVIDENCE_INVALID", "DISTRIBUTION_MISMATCH",
                 "DISTRIBUTION_EVIDENCE_MISSING" ->
                    "Controlled-agent distribution identity evidence is absent or invalid.";
            case "SPI_ATTESTATION_EVIDENCE_INVALID", "SPI_DISCOVERY_INCOMPLETE" ->
                    "Trace-pipeline SPI attestation evidence is absent, malformed, or does not match its pin.";
            case "CLASSLOADER_TOPOLOGY_INVALID", "EXTENSION_LINKAGE_ERROR" ->
                    "Classloader topology evidence is absent or inconsistent.";
            case "EXTENSION_FAILED_WITHOUT_CODE" ->
                    "The extension reported lifecycle=FAILED without a failureCode.";
            case "EXTENSION_INITIALIZING_INCONSISTENT" ->
                    "The extension reported an inconsistent INITIALIZING envelope.";
            case "EXTENSION_LIFECYCLE_UNEXPECTED" ->
                    "The extension lifecycle is not READY, INITIALIZING, or FAILED.";
            case "EXTENSION_READY_WITH_FAILURE_CODE" ->
                    "The extension reported READY with a non-blank failureCode="
                            + display(readiness.lifecycleState().failureCode()) + ".";
            case "UNSPECIFIED_CONTRACT_FAILURE" ->
                    "Observed runtimeState=" + runtimeState
                            + " is not allowed for platform.tracing.enabled=" + tracingEnabled + ".";
            default ->
                    "The Java-agent extension reported this failure under lifecycle="
                            + display(readiness.lifecycleState().lifecycle()) + ".";
        };
    }

    private static String versionMismatchExplanation(String extensionVersion) {
        StringBuilder text = new StringBuilder(
                "Application starter Implementation-Version must equal the Java-agent extension Implementation-Version.");
        if ("development".equals(extensionVersion)) {
            text.append(" extensionVersion=development means the extension classloader did not find MANIFEST.MF ")
                    .append("Implementation-Title=").append(EXPECTED_EXTENSION_IMPLEMENTATION_TITLE)
                    .append("; the packaged extension JAR must use that exact title.");
        } else {
            text.append(" Align the starter and controlled-agent distribution to the same release.");
        }
        return text.toString();
    }

    private static String display(String value) {
        return StringUtils.hasText(value) ? value : "unresolved";
    }
}
