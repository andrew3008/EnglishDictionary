package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationExceptionFixtures.rollbackIncomplete;
import static space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessSnapshotAssertions.failureOf;

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
        Fixture fixture = activeFixture(lifecycle);
        AtomicBoolean staleCallbackInvoked = new AtomicBoolean();
        BootstrapResources resources = new BootstrapResources(mock(ShutdownHookRegistry.class));
        pushStaleTransportCallback(fixture, resources, staleCallbackInvoked);
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");

        // when
        BootstrapException failure = terminalizeActivationFailure(fixture, resources, activationFailure);

        // then
        assertThat(staleCallbackInvoked).isTrue();
        assertThat(failure.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(failure.failure().effectiveCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(failure.getCause()).isSameAs(activationFailure);
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        assertThat(lifecycle.stickyFailure("test").failure().effectiveCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(failureOf(fixture.readiness().snapshot()).effectiveCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(failureOf(fixture.readiness().snapshot()).primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(fixture.journal().current().failureCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(fixture.journal().current().primaryFailureCode()).isEqualTo(ACTIVATION_FAILED);
    }

    @Test
    void cleanup_failure_escalates_activation_failure_without_replacing_its_cause() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixture(lifecycle);
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
        assertThat(failure.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(failure.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(failure.getCause()).isSameAs(activationFailure);
        assertThat(activationFailure.getSuppressed()).singleElement()
                .satisfies(suppressed -> assertThat(suppressed.getCause()).isSameAs(cleanupFailure));
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        BootstrapException stickyFailure = lifecycle.stickyFailure("test");
        assertThat(stickyFailure.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(stickyFailure.getCause()).isSameAs(activationFailure);
        assertThat(failureOf(fixture.readiness().snapshot()).effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(failureOf(fixture.readiness().snapshot()).primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(fixture.journal().current().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(fixture.journal().current().primaryFailureCode()).isEqualTo(ACTIVATION_FAILED);
    }

    @Test
    void fatal_activation_cause_wins_over_fatal_cleanup_and_preserves_cleanup_identity() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixture(lifecycle);
        NoClassDefFoundError activationFailure = new NoClassDefFoundError("simulated fatal activation failure");
        AssertionError cleanupFailure = new AssertionError("simulated fatal cleanup failure");
        BootstrapResources resources = new BootstrapResources(mock(ShutdownHookRegistry.class));
        resources.push(() -> {
            throw cleanupFailure;
        }, "runtime-jmx-controls");

        // when
        // then
        assertThatThrownBy(() -> terminalizeActivationFailure(fixture, resources, activationFailure))
                .isSameAs(activationFailure);
        assertThat(activationFailure.getSuppressed()).containsExactly(cleanupFailure);
        assertThat(lifecycle.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(lifecycle.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
    }

    @Test
    void rollback_marker_escalates_without_mutating_primary_cause_suppressed() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixture(lifecycle);
        Throwable activationFailure = rollbackIncomplete(
                "Runtime MBean rollback was incomplete",
                new IllegalStateException("simulated runtime MBean failure")
        );
        BootstrapResources resources = new BootstrapResources(mock(ShutdownHookRegistry.class));

        // when
        BootstrapException failure = terminalizeActivationFailure(fixture, resources, activationFailure);

        // then
        assertThat(failure.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(failure.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(failure.getCause()).isSameAs(activationFailure);
        assertThat(activationFailure.getSuppressed()).isEmpty();
    }

    @Test
    void publication_failure_preserves_cleanup_failure_as_primary() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapReadinessJournal journal = mock(BootstrapReadinessJournal.class);
        Fixture fixture = activeFixture(lifecycle, journal);
        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        UnsupportedOperationException publicationFailure =
                new UnsupportedOperationException("Readiness publication failed");
        doThrow(publicationFailure).when(fixture.journal()).publishFailed(
                any(BootstrapFailure.class),
                eq(BATCH_ID),
                same(fixture.evidence())
        );

        // when
        // then
        assertThatThrownBy(() -> fixture.transitions().completeShutdown(
                BATCH_ID,
                fixture.evidence(),
                cleanupFailure
        ))
                .isSameAs(cleanupFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(publicationFailure));
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        assertThat(lifecycle.stickyFailure("test").failure().effectiveCode())
                .isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
    }

    @Test
    void accepted_operation_failure_after_close_is_rejected_without_publication() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapReadinessJournal journal = mock(BootstrapReadinessJournal.class);
        Fixture fixture = activeFixture(lifecycle, journal);
        lifecycle.requireOperable("customization");
        lifecycle.beginClose();
        lifecycle.markClosed();
        IllegalStateException operationFailure = new IllegalStateException("Customization failed after close");

        // when
        BootstrapException rejection = fixture.transitions().terminalizeCustomizationFailure(
                ACTIVATION_FAILED,
                "customization",
                operationFailure,
                fixture.evidence()
        );

        // then
        assertThat(rejection.failure().effectiveCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(rejection.getCause()).isSameAs(operationFailure);
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.CLOSED);
        assertThatThrownBy(lifecycle::failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap failure is not recorded");
        verifyNoInteractions(journal);
    }

    @Test
    void rollback_escalation_after_close_is_rejected_without_publication() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapReadinessJournal journal = mock(BootstrapReadinessJournal.class);
        Fixture fixture = activeFixture(lifecycle, journal);
        lifecycle.beginClose();
        lifecycle.markClosed();
        IllegalStateException cleanupFailure = new IllegalStateException("Cleanup failed after close");

        // when
        fixture.transitions().completeCloseFailure(cleanupFailure, BATCH_ID, fixture.evidence());

        // then
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.CLOSED);
        assertThatThrownBy(lifecycle::failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap failure is not recorded");
        verifyNoInteractions(journal);
    }

    private static Fixture activeFixture(BootstrapLifecycleState lifecycle) {
        PlatformExtensionReadiness readiness = readyReadiness();
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        BootstrapReadinessJournal journal = new BootstrapReadinessJournal(
                lifecycle.monitor(),
                readiness,
                agentInstanceId
        );
        return activeFixture(lifecycle, journal, readiness);
    }

    private static Fixture activeFixture(BootstrapLifecycleState lifecycle, BootstrapReadinessJournal journal) {
        return activeFixture(lifecycle, journal, readyReadiness());
    }

    private static Fixture activeFixture(BootstrapLifecycleState lifecycle,
                                         BootstrapReadinessJournal journal,
                                         PlatformExtensionReadiness readiness) {
        BootstrapTerminalTransitions transitions = new BootstrapTerminalTransitions(
                lifecycle,
                journal,
                new DiagnosticReadinessHandle()
        );

        AutoconfigureSession session = mock(AutoconfigureSession.class);
        ExtensionRuntimeEvidence evidence = ExtensionRuntimeEvidence.unavailable(
                readiness.snapshot().extensionVersion()
        );

        lifecycle.commitActive(session);

        return new Fixture(lifecycle, journal, transitions, readiness, evidence);
    }

    private static void pushStaleTransportCallback(Fixture fixture,
                                                   BootstrapResources resources,
                                                   AtomicBoolean invoked) {
        resources.push(() -> {
            invoked.set(true);
            fixture.transitions().publishTransportStale(BATCH_ID, fixture.evidence());
        }, "readiness-stale-transition");
    }

    private static BootstrapException terminalizeActivationFailure(Fixture fixture,
                                                                   BootstrapResources resources,
                                                                   Throwable activationFailure) {
        BootstrapException classified;
        synchronized (fixture.lifecycle().monitor()) {
            classified = fixture.transitions().recordActivationFailure(
                    ACTIVATION_FAILED,
                    "readiness-publication",
                    activationFailure,
                    BATCH_ID,
                    fixture.evidence()
            );
        }
        return fixture.transitions().completeActivationFailure(
                classified,
                resources,
                activationFailure,
                null,
                BATCH_ID,
                fixture.evidence()
        );
    }

    private static PlatformExtensionReadiness readyReadiness() {
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            readiness.markInstalled(capability);
        }

        readiness.completeInitialization();
        return readiness;
    }

    private record Fixture(BootstrapLifecycleState lifecycle,
                           BootstrapReadinessJournal journal,
                           BootstrapTerminalTransitions transitions,
                           PlatformExtensionReadiness readiness,
                           ExtensionRuntimeEvidence evidence) {
    }
}
