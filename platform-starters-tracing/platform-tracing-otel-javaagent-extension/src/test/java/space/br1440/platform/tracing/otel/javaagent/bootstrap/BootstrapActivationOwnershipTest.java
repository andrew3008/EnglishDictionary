package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.DUPLICATE_ACTIVATION;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BootstrapActivationOwnershipTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activation_failure_before_session_transfer_closes_session(boolean resourcesAllocated) {
        // given
        Fixture fixture = preparedFixture();
        BootstrapResources resources = resourcesAllocated ? new BootstrapResources() : null;
        IllegalStateException cause = new IllegalStateException("Activation failed before session transfer");
        BootstrapException classified = recordFailure(fixture, cause);
        doAnswer(invocation -> {
            assertThat(Thread.holdsLock(fixture.lifecycle().monitor())).isFalse();
            return null;
        }).when(fixture.session()).close();

        // when
        BootstrapException outcome = fixture.transitions().completeActivationFailure(
                classified, resources, cause, null, "", fixture.evidence());
        BootstrapLifecycleState.CloseAcquisition close = fixture.lifecycle().acquireClose();
        fixture.lifecycle().completeClose(close.flight(), null);

        // then
        assertThat(outcome).isSameAs(classified);
        verify(fixture.session(), times(1)).close();
        assertThat(close.context().session()).isNull();
        assertThat(fixture.lifecycle().detachFailedSession()).isNull();
        assertThat(fixture.lifecycle().currentEvidence()).isSameAs(fixture.evidence());
        assertThat(fixture.journal().current().failureCode()).isEqualTo(ACTIVATION_FAILED);
    }

    @Test
    void untransferred_session_cleanup_failure_escalates_activation_failure() {
        // given
        Fixture fixture = preparedFixture();
        IllegalStateException cause = new IllegalStateException("Activation failed before session transfer");
        IllegalStateException cleanupFailure = new IllegalStateException("Prepared session cleanup failed");
        BootstrapException classified = recordFailure(fixture, cause);
        doThrow(cleanupFailure).when(fixture.session()).close();

        // when
        BootstrapException outcome = fixture.transitions().completeActivationFailure(
                classified, null, cause, null, "", fixture.evidence());
        BootstrapLifecycleState.CloseAcquisition close = fixture.lifecycle().acquireClose();
        fixture.lifecycle().completeClose(close.flight(), null);

        // then
        assertThat(outcome.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(outcome.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(outcome.failure().phase()).isEqualTo("activate");
        assertThat(outcome.getCause()).isSameAs(cause);
        assertThat(BootstrapCleanupFailures.contains(cause, cleanupFailure)).isTrue();
        verify(fixture.session(), times(1)).close();
        assertThat(close.context().session()).isNull();
        assertThat(fixture.journal().current().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
    }

    @Test
    void fatal_activation_failure_closes_untransferred_session_before_propagation() {
        // given
        Fixture fixture = preparedFixture();
        NoClassDefFoundError cause = new NoClassDefFoundError("Activation dependency is unavailable");
        BootstrapException classified = recordFailure(fixture, cause);

        // when
        Throwable outcome = catchThrowable(() -> {
            throw fixture.transitions().completeActivationFailure(
                    classified, null, cause, null, "", fixture.evidence());
        });

        // then
        assertThat(outcome).isSameAs(cause);
        verify(fixture.session(), times(1)).close();
        assertThat(fixture.lifecycle().detachFailedSession()).isNull();
        assertThat(fixture.journal().current().failureCode()).isEqualTo(ACTIVATION_FAILED);
    }

    @Test
    void activation_failure_after_transfer_closes_session_only_through_resources() {
        // given
        Fixture fixture = preparedFixture();
        BootstrapResources resources = new BootstrapResources();
        resources.push(fixture.session(), "autoconfigure-session");
        fixture.lifecycle().attachResources(resources);
        IllegalStateException cause = new IllegalStateException("Activation failed after session transfer");
        BootstrapException classified = recordFailure(fixture, cause);

        // when
        BootstrapException outcome = fixture.transitions().completeActivationFailure(
                classified, resources, cause, null, "", fixture.evidence());
        BootstrapLifecycleState.CloseAcquisition close = fixture.lifecycle().acquireClose();
        close.context().resources().close();
        fixture.lifecycle().completeClose(close.flight(), null);

        // then
        assertThat(outcome).isSameAs(classified);
        verify(fixture.session(), times(1)).close();
        assertThat(close.context().session()).isNull();
        assertThat(fixture.lifecycle().detachFailedSession()).isNull();
        assertThat(fixture.lifecycle().currentEvidence()).isSameAs(fixture.evidence());
    }

    @Test
    void duplicate_activation_does_not_close_active_session() {
        // given
        Fixture fixture = preparedFixture();
        BootstrapResources resources = new BootstrapResources();
        resources.push(fixture.session(), "autoconfigure-session");
        fixture.lifecycle().attachResources(resources);
        fixture.lifecycle().commitActive();
        BootstrapActivation activation = new BootstrapActivation(
                fixture.lifecycle(), fixture.journal(), fixture.transitions());
        Runnable installShutdownHook = mock(Runnable.class);

        // when
        Throwable outcome = catchThrowable(() -> activation.activate(
                mock(SdkTracerProvider.class), installShutdownHook));

        // then
        try {
            assertThat(outcome).isInstanceOf(BootstrapException.class);
            assertThat(((BootstrapException) outcome).failure().effectiveCode()).isEqualTo(DUPLICATE_ACTIVATION);
            assertThat(fixture.lifecycle().state()).isEqualTo(BootstrapState.ACTIVE);
            verify(fixture.session(), never()).close();
            verify(installShutdownHook, never()).run();
        } finally {
            resources.close();
        }
    }

    private static Fixture preparedFixture() {
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        ExtensionRuntimeEvidence evidence = ExtensionRuntimeEvidence.unavailable(readiness.snapshot().extensionVersion());
        AutoconfigureSession session = mock(AutoconfigureSession.class);
        when(session.evidence()).thenReturn(evidence);
        lifecycle.beginPrepare();
        lifecycle.completePrepare(session);
        assertThat(lifecycle.beginActivation()).isSameAs(session);
        BootstrapReadinessJournal journal = new BootstrapReadinessJournal(
                lifecycle.monitor(), readiness, AgentInstanceId.random());
        journal.publishInitializing();
        return new Fixture(lifecycle, new BootstrapTerminalTransitions(lifecycle, journal), journal, session, evidence);
    }

    private static BootstrapException recordFailure(Fixture fixture, Throwable cause) {
        return fixture.transitions().recordActivationFailure(
                ACTIVATION_FAILED, "activation-prologue", cause, "", fixture.evidence());
    }

    private record Fixture(BootstrapLifecycleState lifecycle,
                           BootstrapTerminalTransitions transitions,
                           BootstrapReadinessJournal journal,
                           AutoconfigureSession session,
                           ExtensionRuntimeEvidence evidence) {
    }
}
