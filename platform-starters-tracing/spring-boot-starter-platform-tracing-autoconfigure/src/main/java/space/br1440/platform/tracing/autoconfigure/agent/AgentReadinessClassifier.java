package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Objects;
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

    record Decision(AgentRuntimeState state, String reasonCode) {
        Decision {
            Objects.requireNonNull(state, "state");
            reasonCode = (reasonCode == null) ? "" : reasonCode;
        }
    }

    static AgentRuntimeState classify(AgentReadinessSnapshot snapshot, String applicationVersion) {
        return decide(snapshot, applicationVersion).state();
    }

    static Decision decide(AgentReadinessSnapshot snapshot, String applicationVersion) {
        AgentReadinessSnapshot.Protocol protocol = snapshot.protocol();
        if (protocol.protocolVersion() != SUPPORTED_PROTOCOL_VERSION) {
            return new Decision(AgentRuntimeState.EXTENSION_INCOMPATIBLE, "EXTENSION_PROTOCOL_UNSUPPORTED");
        }

        if (!SUPPORTED_PROFILE.equals(protocol.profile())) {
            return new Decision(AgentRuntimeState.EXTENSION_INCOMPATIBLE, "EXTENSION_PROFILE_UNSUPPORTED");
        }

        if (!versionsCompatible(protocol.extensionVersion(), applicationVersion)) {
            return new Decision(AgentRuntimeState.EXTENSION_INCOMPATIBLE, "EXTENSION_VERSION_MISMATCH");
        }

        AgentReadinessSnapshot.Status status = snapshot.status();
        if ("FAILED".equals(status.lifecycle())) {
            return status.failureCode().isBlank()
                    ? new Decision(AgentRuntimeState.EXTENSION_INCOMPATIBLE, "EXTENSION_FAILED_WITHOUT_CODE")
                    : new Decision(AgentRuntimeState.EXTENSION_FAILED, status.failureCode());
        }

        if ("INITIALIZING".equals(status.lifecycle())) {
            return status.failureCode().isBlank() && !capabilitiesComplete(snapshot)
                    ? new Decision(AgentRuntimeState.EXTENSION_INITIALIZING, "")
                    : new Decision(AgentRuntimeState.EXTENSION_INCOMPATIBLE, "EXTENSION_INITIALIZING_INCONSISTENT");
        }

        if (!"READY".equals(status.lifecycle())) {
            return new Decision(AgentRuntimeState.EXTENSION_INCOMPATIBLE, "EXTENSION_LIFECYCLE_UNEXPECTED");
        }

        if (!status.failureCode().isBlank()) {
            return new Decision(AgentRuntimeState.EXTENSION_INCOMPATIBLE, "EXTENSION_READY_WITH_FAILURE_CODE");
        }

        if (!capabilitiesComplete(snapshot)) {
            return new Decision(AgentRuntimeState.CAPABILITY_MISSING, "CAPABILITY_SET_MISMATCH");
        }

        return new Decision(AgentRuntimeState.AGENT_READY, "");
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
