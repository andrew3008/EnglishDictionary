package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailureCodes.CONFIGURATION_INITIALIZATION_FAILED;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailure;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.FailureAdmission;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.FailureContext;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.FailureSite;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class FailureHandlerTest {

    @Test
    void handle_records_failure_closes_claimed_session_once_and_registers_diagnostics() {
        // given
        Fixture fixture = preparedFixture();
        IllegalStateException cause = new IllegalStateException("Prepare failed");

        // when
        BootstrapFailure failure = fixture.handler().handle(
                FailureSite.prepareConfiguration(),
                CONFIGURATION_INITIALIZATION_FAILED,
                cause,
                null,
                fixture.evidence()
        );

        // then
        assertThat(failure.cause()).isSameAs(cause);
        assertThat(fixture.lifecycle().state()).isEqualTo(BootstrapState.FAILED);
        assertThat(fixture.registrations()).hasValue(1);
        verify(fixture.session()).close();
    }

    @Test
    void handle_under_held_monitor_is_rejected_before_recording() {
        // given
        Fixture fixture = preparedFixture();

        // when
        synchronized (fixture.lifecycle().monitor()) {
            assertThatThrownBy(() -> fixture.handler().handle(
                    FailureSite.prepareConfiguration(),
                    CONFIGURATION_INITIALIZATION_FAILED,
                    new IllegalStateException("x"),
                    null,
                    fixture.evidence()
            ))
                    .isInstanceOf(IllegalMonitorStateException.class)
                    .hasMessage("Bootstrap failure handling must run outside the bootstrap lifecycle monitor");

            // then
            assertThat(fixture.lifecycle().state()).isEqualTo(BootstrapState.PREPARED);
        }
    }

    @Test
    void complete_under_held_monitor_is_rejected_before_release() {
        // given
        Fixture fixture = preparedFixture();
        IllegalStateException cause = new IllegalStateException("x");
        FailureAdmission admission = fixture.transitions().terminalize(
                FailureSite.prepareConfiguration(),
                CONFIGURATION_INITIALIZATION_FAILED,
                cause,
                BatchIds.NONE,
                fixture.evidence()
        );
        FailureContext context = new FailureContext(
                FailureSite.prepareConfiguration(),
                CONFIGURATION_INITIALIZATION_FAILED,
                cause,
                admission,
                null,
                null,
                BatchIds.NONE,
                fixture.evidence()
        );

        // when
        synchronized (fixture.lifecycle().monitor()) {
            assertThatThrownBy(() -> fixture.handler().complete(context))
                    .isInstanceOf(IllegalMonitorStateException.class);
        }

        // then
        verify(fixture.session(), never()).close();
    }

    @Test
    void missing_preparation_under_held_monitor_is_not_a_monitor_violation() {
        // given
        Fixture fixture = preparedFixture();
        //noinspection resource -- beginActivation возвращает mock-сессию фикстуры, закрытие ей не принадлежит
        fixture.lifecycle().beginActivation();

        // when
        synchronized (fixture.lifecycle().monitor()) {
            assertThat(fixture.handler().handleMissingPreparation(fixture.evidence())).isEmpty();
        }
    }

    @Test
    void startup_failure_from_active_changes_nothing() {
        // given
        Fixture fixture = preparedFixture();
        //noinspection resource -- beginActivation возвращает mock-сессию фикстуры, закрытие ей не принадлежит
        fixture.lifecycle().beginActivation();
        fixture.lifecycle().attachResources(new BootstrapResources());
        fixture.lifecycle().commitActive();
        ConfigurationException cause = new ConfigurationException("foreign afterAgent");

        // when
        fixture.handler().handleStartup(cause, fixture.evidence());

        // then
        assertThat(fixture.lifecycle().state()).isEqualTo(BootstrapState.ACTIVE);
        assertThat(fixture.registrations()).hasValue(0);
        assertThat(cause.getSuppressed()).isEmpty();
        verify(fixture.session(), never()).close();
    }

    private static Fixture preparedFixture() {
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        ExtensionRuntimeEvidence evidence = ExtensionRuntimeEvidence.unavailable(
                readiness.snapshot().extensionVersion()
        );
        AutoconfigureSession session = mock(AutoconfigureSession.class);
        when(session.evidence()).thenReturn(evidence);
        lifecycle.beginPrepare();
        lifecycle.completePrepare(session);
        BootstrapReadinessJournal journal = new BootstrapReadinessJournal(
                lifecycle.monitor(),
                readiness,
                AgentInstanceId.random()
        );
        journal.publishInitializing();
        BootstrapTerminalTransitions transitions = new BootstrapTerminalTransitions(lifecycle, journal);
        AtomicInteger registrations = new AtomicInteger();
        return new Fixture(
                lifecycle,
                transitions,
                new FailureHandler(lifecycle, transitions, registrations::incrementAndGet),
                session,
                evidence,
                registrations
        );
    }

    private record Fixture(BootstrapLifecycleState lifecycle,
                           BootstrapTerminalTransitions transitions,
                           FailureHandler handler,
                           AutoconfigureSession session,
                           ExtensionRuntimeEvidence evidence,
                           AtomicInteger registrations) {
    }
}
