package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

class BootstrapTerminalTransitionsTest {

    @Test
    void publication_failure_preserves_cleanup_failure_as_primary() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapReadinessJournal journal = mock(BootstrapReadinessJournal.class);
        BootstrapTerminalTransitions transitions = new BootstrapTerminalTransitions(
                lifecycle,
                journal,
                new DiagnosticReadinessHandle()
        );
        AutoconfigureSession session = mock(AutoconfigureSession.class);
        PlatformExtensionReadiness readiness = mock(PlatformExtensionReadiness.class);
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        ExtensionRuntimeEvidence evidence = ExtensionRuntimeEvidence.unavailable("test");
        when(session.readiness()).thenReturn(readiness);
        when(session.agentInstanceId()).thenReturn(agentInstanceId);

        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        UnsupportedOperationException publicationFailure =
                new UnsupportedOperationException("Readiness publication failed");
        doThrow(publicationFailure).when(journal).publishFailed(
                same(readiness),
                same(agentInstanceId),
                eq("FAILED_ROLLBACK_INCOMPLETE"),
                eq("batch-1"),
                same(evidence)
        );

        // when
        // then
        assertThatThrownBy(() -> transitions.completeShutdown(
                session,
                "batch-1",
                evidence,
                cleanupFailure
        ))
                .isSameAs(cleanupFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(publicationFailure));
    }
}
