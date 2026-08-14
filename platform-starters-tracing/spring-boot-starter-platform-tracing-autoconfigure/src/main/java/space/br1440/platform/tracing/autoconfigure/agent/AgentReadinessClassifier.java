package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Set;

import lombok.experimental.UtilityClass;

@UtilityClass
final class AgentReadinessClassifier {

    static final int SUPPORTED_PROTOCOL_VERSION = 1;
    static final String SUPPORTED_PROFILE = "platform-agent-secure-v1";

    private static final Set<String> EXPECTED_CAPABILITIES = Set.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED"
    );

    static AgentRuntimeState classify(AgentReadinessSnapshot snapshot, String applicationVersion) {
        AgentReadinessSnapshot.Protocol protocol = snapshot.protocol();
        if (protocol.protocolVersion() != SUPPORTED_PROTOCOL_VERSION
                || !SUPPORTED_PROFILE.equals(protocol.profile())
                || !versionsCompatible(protocol.extensionVersion(), applicationVersion)) {
            return AgentRuntimeState.EXTENSION_INCOMPATIBLE;
        }

        AgentReadinessSnapshot.Status status = snapshot.status();
        if ("FAILED".equals(status.lifecycle())) {
            return status.failureCode().isBlank()
                    ? AgentRuntimeState.EXTENSION_INCOMPATIBLE
                    : AgentRuntimeState.EXTENSION_FAILED;
        }

        if ("INITIALIZING".equals(status.lifecycle())) {
            return status.failureCode().isBlank()
                    && !capabilitiesComplete(snapshot)
                    ? AgentRuntimeState.EXTENSION_INITIALIZING
                    : AgentRuntimeState.EXTENSION_INCOMPATIBLE;
        }

        if (!"READY".equals(status.lifecycle()) || !status.failureCode().isBlank()) {
            return AgentRuntimeState.EXTENSION_INCOMPATIBLE;
        }

        if (!capabilitiesComplete(snapshot)) {
            return AgentRuntimeState.CAPABILITY_MISSING;
        }

        return AgentRuntimeState.AGENT_READY;
    }

    private static boolean capabilitiesComplete(AgentReadinessSnapshot snapshot) {
        return EXPECTED_CAPABILITIES.equals(snapshot.capabilities());
    }

    private static boolean versionsCompatible(String extensionVersion, String applicationVersion) {
        if ("development".equals(extensionVersion) && "development".equals(applicationVersion)) {
            return true;
        }

        return (applicationVersion != null) && applicationVersion.equals(extensionVersion);
    }
}
