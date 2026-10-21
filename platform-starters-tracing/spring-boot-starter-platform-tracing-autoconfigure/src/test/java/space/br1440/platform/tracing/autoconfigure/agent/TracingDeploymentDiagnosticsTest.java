package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class TracingDeploymentDiagnosticsTest {

    @Test
    void observedCarriesPrimaryFailureCodeOnlyWhenItBelongsToTheStarterFailureCode() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.failedSnapshot(
                "ACTIVATION_FAILED",
                "FAILED_ROLLBACK_INCOMPLETE");

        // when
        TracingDeploymentDiagnostics.Observed matching = TracingDeploymentDiagnostics.Observed.of(
                snapshot,
                "FAILED_ROLLBACK_INCOMPLETE");
        TracingDeploymentDiagnostics.Observed overridden = TracingDeploymentDiagnostics.Observed.of(
                snapshot,
                "READINESS_ANCHOR_CONTENT_DRIFT");

        // then
        assertThat(matching.failureCode()).isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
        assertThat(matching.primaryFailureCode()).isEqualTo("ACTIVATION_FAILED");
        assertThat(overridden.failureCode()).isEqualTo("READINESS_ANCHOR_CONTENT_DRIFT");
        assertThat(overridden.primaryFailureCode()).isEmpty();
        assertThat(overridden.snapshot().lifecycleState().failureCode()).isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
        assertThat(overridden.snapshot().lifecycleState().primaryFailureCode()).isEqualTo("ACTIVATION_FAILED");
    }

    @Test
    void absentAndUnreadableReadinessCarryOnlyTheStarterFailureCode() {
        // then
        assertThat(new TracingDeploymentDiagnostics.EndpointAbsent("CONTROLLED_AGENT_MISSING").failureCode())
                .isEqualTo("CONTROLLED_AGENT_MISSING");
        assertThat(new TracingDeploymentDiagnostics.SnapshotUnreadable("READINESS_INVOCATION_FAILED").failureCode())
                .isEqualTo("READINESS_INVOCATION_FAILED");
        assertThatNullPointerException()
                .isThrownBy(() -> new TracingDeploymentDiagnostics.EndpointAbsent(null))
                .withMessage("failureCode");
        assertThatNullPointerException()
                .isThrownBy(() -> new TracingDeploymentDiagnostics.SnapshotUnreadable(null))
                .withMessage("failureCode");
        assertThatNullPointerException()
                .isThrownBy(() -> TracingDeploymentDiagnostics.Observed.of(null, "READINESS_ANCHOR_CONTENT_DRIFT"))
                .withMessage("snapshot");
        assertThatNullPointerException()
                .isThrownBy(() -> TracingDeploymentDiagnostics.Observed.of(
                        ReadinessTestFixtures.readySnapshot(),
                        null))
                .withMessage("failureCode");
    }

    @Test
    void disabledDiagnosticsDoesNotClaimAnEndpointWasProbed() {
        // when
        TracingDeploymentDiagnostics diagnostics = TracingDeploymentDiagnostics.withoutAgentData(
                false,
                false,
                AgentRuntimeState.DISABLED,
                "");

        // then
        assertThat(diagnostics.agentReadiness()).isEqualTo(new TracingDeploymentDiagnostics.EndpointAbsent(""));
    }

    @Test
    void unreadableFactoryDoesNotInventSnapshotFields() {
        // when
        TracingDeploymentDiagnostics diagnostics = TracingDeploymentDiagnostics.withUnreadableAgentData(
                true,
                true,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_INVOCATION_FAILED");

        // then
        assertThat(diagnostics.agentReadiness())
                .isEqualTo(new TracingDeploymentDiagnostics.SnapshotUnreadable("READINESS_INVOCATION_FAILED"));
    }
}
