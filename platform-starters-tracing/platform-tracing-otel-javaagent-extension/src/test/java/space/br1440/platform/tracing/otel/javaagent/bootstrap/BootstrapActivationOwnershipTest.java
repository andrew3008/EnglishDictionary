package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.DUPLICATE_ACTIVATION;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BootstrapActivationOwnershipTest {

    private static final FailureSite PROLOGUE = FailureSite.activation("activation-prologue");

    @Test
    void activation_failure_before_session_transfer_claims_session_in_failure_section() {
        // given
        Fixture fixture = preparedFixture(PlatformExtensionReadiness.forCurrentExtension());
        IllegalStateException cause = new IllegalStateException("Activation failed before session transfer");

        // when
        FailureAdmission admission = recordFailure(fixture, cause);
        BootstrapLifecycleState.CloseAcquisition close = fixture.lifecycle().acquireClose();
        fixture.lifecycle().completeClose(close.flight(), null);

        // then
        assertThat(admission.kind()).isEqualTo(FailureAdmission.Kind.RECORDED);
        assertThat(admission.claimedSession()).isSameAs(fixture.session());
        assertThat(admission.projectionFailure()).isNull();
        assertThat(admission.failure().cause()).isSameAs(cause);
        assertThat(admission.failure().phase()).isEqualTo("activate");
        assertThat(close.context().session()).isNull();
        assertThat(close.context().resources()).isNull();
        verify(fixture.session(), never()).close();
        assertThat(fixture.lifecycle().currentEvidence()).isSameAs(fixture.evidence());
        assertThat(fixture.journal().current().failureCode()).isEqualTo(ACTIVATION_FAILED);
    }

    @Test
    void activation_failure_after_transfer_leaves_session_owned_by_resources() {
        // given
        Fixture fixture = preparedFixture(PlatformExtensionReadiness.forCurrentExtension());
        BootstrapResources resources = new BootstrapResources();
        resources.push(fixture.session(), "autoconfigure-session");
        fixture.lifecycle().attachResources(resources);
        IllegalStateException cause = new IllegalStateException("Activation failed after session transfer");

        // when
        FailureAdmission admission = recordFailure(fixture, cause);
        BootstrapLifecycleState.CloseAcquisition close = fixture.lifecycle().acquireClose();
        close.context().resources().close();
        fixture.lifecycle().completeClose(close.flight(), null);

        // then
        assertThat(admission.kind()).isEqualTo(FailureAdmission.Kind.RECORDED);
        assertThat(admission.claimedSession()).isNull();
        assertThat(close.context().resources()).isSameAs(resources);
        assertThat(close.context().session()).isNull();
        verify(fixture.session()).close();
    }

    @Test
    void repeated_failure_is_already_failed_without_claim_or_publication() {
        // given
        Fixture fixture = preparedFixture(PlatformExtensionReadiness.forCurrentExtension());
        IllegalStateException cause = new IllegalStateException("First activation failure");
        IllegalStateException repeated = new IllegalStateException("Repeated activation failure");
        FailureAdmission first = recordFailure(fixture, cause);

        // when
        FailureAdmission second = recordFailure(fixture, repeated);

        // then
        assertThat(second.kind()).isEqualTo(FailureAdmission.Kind.ALREADY_FAILED);
        assertThat(second.failure()).isSameAs(first.failure());
        assertThat(second.claimedSession()).isNull();
        assertThat(second.projectionFailure()).isNull();
        assertThat(repeated.getSuppressed()).isEmpty();
    }

    @Test
    void failed_projection_is_captured_without_suppression_or_throw() {
        // given
        PlatformExtensionReadiness readiness = spy(PlatformExtensionReadiness.forCurrentExtension());
        Fixture fixture = preparedFixture(readiness);
        IllegalStateException cause = new IllegalStateException("Activation failed");
        IllegalStateException publicationFailure = new IllegalStateException("Failure publication failed");
        doThrow(publicationFailure).when(readiness).fail(anyString(), anyString());

        // when
        FailureAdmission admission = recordFailure(fixture, cause);

        // then
        assertThat(admission.kind()).isEqualTo(FailureAdmission.Kind.RECORDED);
        assertThat(admission.projectionFailure()).isSameAs(publicationFailure);
        assertThat(admission.claimedSession()).isSameAs(fixture.session());
        assertThat(cause.getSuppressed()).isEmpty();
        assertThat(fixture.lifecycle().state()).isEqualTo(BootstrapState.FAILED);
    }

    @Test
    void duplicate_activation_is_rejected_without_closing_active_session() {
        // given
        Fixture fixture = preparedFixture(PlatformExtensionReadiness.forCurrentExtension());
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
            assertThat(outcome).isInstanceOf(BootstrapAdmissionRejectedException.class);
            assertThat(((BootstrapAdmissionRejectedException) outcome).primaryCode()).isEqualTo(DUPLICATE_ACTIVATION);
            assertThat(fixture.lifecycle().state()).isEqualTo(BootstrapState.ACTIVE);
            verify(fixture.session(), never()).close();
            verify(installShutdownHook, never()).run();
        } finally {
            resources.close();
        }
    }

    private static Fixture preparedFixture(PlatformExtensionReadiness readiness) {
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
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

    private static FailureAdmission recordFailure(Fixture fixture, Throwable cause) {
        return fixture.transitions().terminalize(PROLOGUE, ACTIVATION_FAILED, cause, "", fixture.evidence());
    }

    private record Fixture(BootstrapLifecycleState lifecycle,
                           BootstrapTerminalTransitions transitions,
                           BootstrapReadinessJournal journal,
                           AutoconfigureSession session,
                           ExtensionRuntimeEvidence evidence) {
    }
}
