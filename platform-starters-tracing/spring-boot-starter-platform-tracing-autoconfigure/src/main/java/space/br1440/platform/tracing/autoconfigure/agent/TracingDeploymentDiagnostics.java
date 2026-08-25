package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Objects;
import java.util.Set;

public record TracingDeploymentDiagnostics(boolean tracingEnabled,
                                           boolean agentMarkerVisible,
                                           AgentRuntimeState runtimeState,
                                           AgentReadiness agentReadiness) {

    public TracingDeploymentDiagnostics {
        Objects.requireNonNull(runtimeState, "runtimeState");
        Objects.requireNonNull(agentReadiness, "agentReadiness");
    }

    public static TracingDeploymentDiagnostics withoutAgentData(boolean tracingEnabled,
                                                                boolean agentMarkerVisible,
                                                                AgentRuntimeState runtimeState,
                                                                String failureCode) {

        return new TracingDeploymentDiagnostics(
                tracingEnabled,
                agentMarkerVisible,
                runtimeState,
                AgentReadiness.unavailable(failureCode)
        );
    }

    public record AgentReadiness(boolean endpointPresent,
                                 String extensionVersion,
                                 int protocolVersion,
                                 String lifecycle,
                                 String failureCode,
                                 Set<String> capabilities) {

        public AgentReadiness {
            extensionVersion = normalize(extensionVersion);
            lifecycle = normalize(lifecycle);
            failureCode = normalize(failureCode);
            capabilities = (capabilities == null) ? Set.of() : Set.copyOf(capabilities);
        }

        public static AgentReadiness unavailable(String failureCode) {
            return new AgentReadiness(
                    false,
                    "",
                    -1,
                    "",
                    failureCode,
                    Set.of()
            );
        }

        private static String normalize(String value) {
            return (value == null) ? "" : value;
        }
    }
}
