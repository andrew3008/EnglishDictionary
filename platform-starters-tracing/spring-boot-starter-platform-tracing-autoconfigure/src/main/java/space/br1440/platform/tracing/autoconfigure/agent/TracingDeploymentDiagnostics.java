package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Objects;

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
                new EndpointAbsent(failureCode)
        );
    }

    static TracingDeploymentDiagnostics withUnreadableAgentData(boolean tracingEnabled,
                                                                boolean agentMarkerVisible,
                                                                AgentRuntimeState runtimeState,
                                                                String failureCode) {
        return new TracingDeploymentDiagnostics(
                tracingEnabled,
                agentMarkerVisible,
                runtimeState,
                new SnapshotUnreadable(failureCode)
        );
    }

    static TracingDeploymentDiagnostics withAgentData(boolean tracingEnabled,
                                                      boolean agentMarkerVisible,
                                                      AgentRuntimeState runtimeState,
                                                      AgentReadinessSnapshot snapshot,
                                                      String failureCode) {
        return new TracingDeploymentDiagnostics(
                tracingEnabled,
                agentMarkerVisible,
                runtimeState,
                Observed.of(snapshot, failureCode)
        );
    }

    public sealed interface AgentReadiness permits EndpointAbsent, SnapshotUnreadable, Observed {

        String failureCode();
    }

    public record EndpointAbsent(String failureCode) implements AgentReadiness {

        public EndpointAbsent {
            Objects.requireNonNull(failureCode, "failureCode");
        }
    }

    public record SnapshotUnreadable(String failureCode) implements AgentReadiness {

        public SnapshotUnreadable {
            Objects.requireNonNull(failureCode, "failureCode");
        }
    }

    public record Observed(AgentReadinessSnapshot snapshot, String failureCode) implements AgentReadiness {

        public Observed {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(failureCode, "failureCode");
        }

        public static Observed of(AgentReadinessSnapshot snapshot, String failureCode) {
            return new Observed(snapshot, failureCode);
        }

        public String primaryFailureCode() {
            AgentReadinessSnapshot.LifecycleState lifecycleState = snapshot.lifecycleState();
            return lifecycleState.failureCode().equals(failureCode)
                    ? lifecycleState.primaryFailureCode()
                    : "";
        }
    }
}
