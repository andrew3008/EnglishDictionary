package space.br1440.platform.tracing.autoconfigure.agent;

import lombok.experimental.UtilityClass;

@UtilityClass
final class AgentReadinessClassifier {

    static final int SUPPORTED_PROTOCOL_VERSION = 1;
    static final String SUPPORTED_PROFILE = "platform-agent-secure-v1";

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
                    && snapshot.capabilities().mismatchesRequired()
                    ? AgentRuntimeState.EXTENSION_INITIALIZING
                    : AgentRuntimeState.EXTENSION_INCOMPATIBLE;
        }

        if (!"READY".equals(status.lifecycle()) || !status.failureCode().isBlank()) {
            return AgentRuntimeState.EXTENSION_INCOMPATIBLE;
        }

        if (snapshot.capabilities().mismatchesRequired() || !snapshot.installFlags().isComplete()) {
            return AgentRuntimeState.CAPABILITY_MISSING;
        }

        return AgentRuntimeState.AGENT_READY;
    }

    private static boolean versionsCompatible(String extensionVersion, String applicationVersion) {
        if ("development".equals(extensionVersion) && "development".equals(applicationVersion)) {
            return true;
        }

        return (applicationVersion != null) && applicationVersion.equals(extensionVersion);
    }
}
