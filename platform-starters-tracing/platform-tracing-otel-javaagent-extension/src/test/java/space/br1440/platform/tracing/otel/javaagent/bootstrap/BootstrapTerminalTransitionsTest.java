package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.ShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

class BootstrapTerminalTransitionsTest {

    private static final String BATCH_ID = "batch-1";

    @Test
    void activation_failure_wins_over_stale_callback_during_rollback() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixture(lifecycle, new BootstrapReadinessJournal(lifecycle.monitor()));
        AtomicBoolean staleCallbackInvoked = new AtomicBoolean();
        BootstrapResources resources = new BootstrapResources(mock(ShutdownHookRegistry.class));
        pushStaleTransportCallback(fixture, resources, staleCallbackInvoked);
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");

        // when
        BootstrapException failure = terminalizeActivationFailure(fixture, resources, activationFailure);

        // then
        assertThat(staleCallbackInvoked).isTrue();
        assertThat(failure.failure().code()).isEqualTo(ACTIVATION_FAILED);
        assertThat(failure.getCause()).isSameAs(activationFailure);
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        assertThat(lifecycle.stickyFailure("test").failure().code()).isEqualTo(ACTIVATION_FAILED);
        assertThat(fixture.readiness().snapshot().failureCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(fixture.journal().current().failureCode()).isEqualTo(ACTIVATION_FAILED);
    }

    @Test
    void cleanup_failure_escalates_activation_failure_without_replacing_its_cause() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixture(lifecycle, new BootstrapReadinessJournal(lifecycle.monitor()));
        AtomicBoolean staleCallbackInvoked = new AtomicBoolean();
        IllegalStateException cleanupFailure =
                new IllegalStateException("Runtime controls cleanup failed");
        BootstrapResources resources = new BootstrapResources(mock(ShutdownHookRegistry.class));
        resources.push(() -> {
            throw cleanupFailure;
        }, "runtime-jmx-controls");
        pushStaleTransportCallback(fixture, resources, staleCallbackInvoked);
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");

        // when
        BootstrapException failure = terminalizeActivationFailure(fixture, resources, activationFailure);

        // then
        assertThat(staleCallbackInvoked).isTrue();
        assertThat(failure.failure().code()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(failure.getCause()).isSameAs(activationFailure);
        assertThat(activationFailure.getSuppressed()).singleElement()
                .satisfies(suppressed -> assertThat(suppressed.getCause()).isSameAs(cleanupFailure));
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        BootstrapException stickyFailure = lifecycle.stickyFailure("test");
        assertThat(stickyFailure.failure().code()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(stickyFailure.getCause()).isSameAs(activationFailure);
        assertThat(fixture.readiness().snapshot().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(fixture.journal().current().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
    }

    @Test
    void publication_failure_preserves_cleanup_failure_as_primary() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixture(lifecycle, mock(BootstrapReadinessJournal.class));
        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        UnsupportedOperationException publicationFailure =
                new UnsupportedOperationException("Readiness publication failed");
        doThrow(publicationFailure).when(fixture.journal()).publishFailed(
                same(fixture.readiness()),
                same(fixture.agentInstanceId()),
                eq(FAILED_ROLLBACK_INCOMPLETE),
                eq(BATCH_ID),
                same(fixture.evidence())
        );

        // when
        // then
        assertThatThrownBy(() -> fixture.transitions().completeShutdown(
                fixture.session(),
                BATCH_ID,
                fixture.evidence(),
                cleanupFailure
        ))
                .isSameAs(cleanupFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(publicationFailure));
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        assertThat(lifecycle.stickyFailure("test").failure().code()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(fixture.readiness().snapshot().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
    }

    private static Fixture activeFixture(BootstrapLifecycleState lifecycle, BootstrapReadinessJournal journal) {
        BootstrapTerminalTransitions transitions = new BootstrapTerminalTransitions(
                lifecycle,
                journal,
                new DiagnosticReadinessHandle()
        );

        AutoconfigureSession session = mock(AutoconfigureSession.class);
        PlatformExtensionReadiness readiness = readyReadiness();
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        ExtensionRuntimeEvidence evidence = ExtensionRuntimeEvidence.unavailable(
                readiness.snapshot().extensionVersion()
        );

        when(session.readiness()).thenReturn(readiness);
        when(session.agentInstanceId()).thenReturn(agentInstanceId);
        lifecycle.commitActive(session);

        return new Fixture(journal, transitions, session, readiness, agentInstanceId, evidence);
    }

    private static void pushStaleTransportCallback(Fixture fixture,
                                                   BootstrapResources resources,
                                                   AtomicBoolean invoked) {
        resources.push(() -> {
            invoked.set(true);
            fixture.transitions().publishTransportStale(fixture.session(), BATCH_ID, fixture.evidence());
        }, "readiness-stale-transition");
    }

    private static BootstrapException terminalizeActivationFailure(Fixture fixture,
                                                                   BootstrapResources resources,
                                                                   Throwable activationFailure) {
        return fixture.transitions().terminalizeActivationFailure(
                fixture.session(),
                resources,
                ACTIVATION_FAILED,
                "readiness-publication",
                activationFailure,
                BATCH_ID,
                fixture.evidence()
        );
    }

    private static PlatformExtensionReadiness readyReadiness() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            readiness.markInstalled(capability);
        }

        readiness.completeInitialization();
        return readiness;
    }

    private record Fixture(BootstrapReadinessJournal journal,
                           BootstrapTerminalTransitions transitions,
                           AutoconfigureSession session,
                           PlatformExtensionReadiness readiness,
                           AgentInstanceId agentInstanceId,
                           ExtensionRuntimeEvidence evidence) {
    }
}
