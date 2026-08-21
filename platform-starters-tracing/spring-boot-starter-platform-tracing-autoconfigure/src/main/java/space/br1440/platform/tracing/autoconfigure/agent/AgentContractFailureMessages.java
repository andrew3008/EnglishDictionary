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
        String reason = StringUtils.hasText(readiness.failureCode())
                ? readiness.failureCode()
                : "UNSPECIFIED_CONTRACT_FAILURE";

        StringBuilder message = new StringBuilder();
        message.append("platform.tracing.enabled=").append(tracingEnabled)
                .append(" rejected Controlled Platform Agent: runtimeState=")
                .append(diagnostics.runtimeState())
                .append(", reason=").append(reason);

        if (readiness.endpointPresent() && readiness.protocolVersion() >= 0) {
            message.append(", applicationVersion=").append(display(applicationVersion))
                    .append(", extensionVersion=").append(display(readiness.extensionVersion()))
                    .append(", protocolVersion=").append(readiness.protocolVersion())
                    .append(" (supported=").append(AgentReadinessClassifier.SUPPORTED_PROTOCOL_VERSION).append(')')
                    .append(", lifecycle=").append(display(readiness.lifecycle()));
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
                    "The OpenTelemetry Java agent loaded, but the Platform Tracing extension readiness endpoint is absent. "
                            + "Use the platform-tracing-javaagent distribution that embeds "
                            + "extensions/platform-tracing-otel-javaagent-extension.jar.";

            case "APPLICATION_RUNTIME_DETECTED" ->
                    "An application-owned OpenTelemetry runtime was detected. "
                            + "Remove application OpenTelemetry beans and GlobalOpenTelemetry setup; "
                            + "the Controlled Platform Agent owns the SDK.";

            case "CONTROLLED_AGENT_FORBIDDEN" ->
                    "platform.tracing.enabled=false forbids a loaded Controlled Platform Agent. "
                            + "Remove -javaagent or set platform.tracing.enabled=true.";

            case "READINESS_ENDPOINT_DISAPPEARED" ->
                    "The extension readiness MBean disappeared after it was observed. "
                            + "The Java-agent extension must keep the readiness endpoint registered for the process lifetime.";

            case "READINESS_ACCESS_DENIED" ->
                    "The application was denied access to the extension readiness MBean.";

            case "READINESS_INVOCATION_FAILED" ->
                    "Reading the extension readiness MBean failed. Inspect the Java-agent extension logs for the root cause.";

            case "MALFORMED_READINESS_DESCRIPTOR" ->
                    "The extension readiness MBean descriptor is incomplete or malformed.";

            case "EXTENSION_READINESS_TIMEOUT" ->
                    "The extension stayed in INITIALIZING until the application startup readiness timeout elapsed.";

            case "EXTENSION_VERSION_MISMATCH" -> versionMismatchExplanation(readiness.extensionVersion());

            case "EXTENSION_PROTOCOL_UNSUPPORTED" ->
                    "The extension protocolVersion is not supported by this starter "
                            + "(supported=" + AgentReadinessClassifier.SUPPORTED_PROTOCOL_VERSION + ").";

            case "EXTENSION_PROFILE_UNSUPPORTED" ->
                    "The extension profile is not supported by this starter "
                            + "(supported=" + AgentReadinessClassifier.SUPPORTED_PROFILE + ").";

            case "EXTENSION_FAILED_WITHOUT_CODE" ->
                    "The extension reported lifecycle=FAILED without a failureCode.";

            case "EXTENSION_INITIALIZING_INCONSISTENT" ->
                    "The extension reported INITIALIZING with a complete capability set or a non-blank failureCode.";

            case "EXTENSION_LIFECYCLE_UNEXPECTED" ->
                    "The extension lifecycle is not READY, INITIALIZING, or FAILED.";

            case "EXTENSION_READY_WITH_FAILURE_CODE" ->
                    "The extension reported READY with a non-blank failureCode="
                            + display(readiness.failureCode()) + ".";

            case "CAPABILITY_SET_MISMATCH" ->
                    "Readiness capabilities must equal the secure-profile set exactly. observedCapabilities="
                            + readiness.capabilities() + ".";

            case "UNSPECIFIED_CONTRACT_FAILURE" ->
                    "Observed runtimeState=" + runtimeState
                            + " is not allowed for platform.tracing.enabled=" + tracingEnabled + ".";

            default ->
                    "The Java-agent extension reported this failure under lifecycle="
                            + display(readiness.lifecycle()) + ".";
        };
    }

    private static String versionMismatchExplanation(String extensionVersion) {
        StringBuilder text = new StringBuilder("Application starter Implementation-Version must equal the Java-agent extension Implementation-Version.");
        if ("development".equals(extensionVersion)) {
            text.append(" extensionVersion=development means the extension classloader did not find MANIFEST.MF ")
                    .append("Implementation-Title=").append(EXPECTED_EXTENSION_IMPLEMENTATION_TITLE)
                    .append("; the packaged extension JAR must use that exact title, not a suffixed title.");
        } else {
            text.append(" Align the Spring Boot tracing starter and the platform-tracing-javaagent distribution to the same release.");
        }

        return text.toString();
    }

    private static String display(String value) {
        return StringUtils.hasText(value) ? value : "unresolved";
    }
}
