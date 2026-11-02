package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.catchBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.AGENT_STARTUP_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrarTestSupport;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class BootstrapCoordinatorStartupFailureTest {

    private static final String CUSTOMIZATION_FAILED = "SPAN_PROCESSOR_INITIALIZATION_FAILED";

    @Test
    void startup_failure_releases_session_once_and_retains_failed_diagnostics_until_close() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        ConfigurationException cause = new ConfigurationException("Invalid OTLP endpoint");
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();

        try (coordinator) {
            AutoconfigureSession session = coordinator.prepare(config());

            // when
            coordinator.onStartupFailure(cause);

            // then
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.currentWire().primaryFailureCode()).isEqualTo(AGENT_STARTUP_FAILED);
            assertThat(coordinator.currentWire().evidence()).isEqualTo(session.evidence());
            assertThat(coordinator.currentWire().agentInstanceId()).isEqualTo(session.agentInstanceId().toString());
            assertThatDiagnosticMBean(server).isFailedWith(AGENT_STARTUP_FAILED)
                    .hasSnapshotEntry(JmxWireContract.FIELD_READINESS_SNAPSHOT_PRIMARY_FAILURE_CODE, AGENT_STARTUP_FAILED);
            assertThat(server.isRegistered(DomainObjectNames.EXPORT)).isFalse();
            assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
            assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isFalse();
            assertThat(stickyFailure(coordinator, AGENT_STARTUP_FAILED).getCause()).isSameAs(cause);
            verify(runtime).close();
            assertThatCode(coordinator::close).doesNotThrowAnyException();
            assertThatDiagnosticMBean(server).isNotRegistered();
        }

        verify(runtime).close();
    }

    @Test
    void startup_cleanup_failure_preserves_primary_and_is_not_replayed() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        ConfigurationException cause = new ConfigurationException("Invalid OTLP endpoint");
        IllegalStateException cleanupFailure = new IllegalStateException("Scrubbing cleanup failed");
        doThrow(cleanupFailure).when(runtime).close();
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();

        try (coordinator) {
            coordinator.prepare(config());

            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.onStartupFailure(cause), FAILED_ROLLBACK_INCOMPLETE
            );
            ExtensionReadinessWireSnapshot failed = coordinator.currentWire();
            coordinator.onStartupFailure(failure);
            coordinator.onStartupFailure(new ConfigurationException("Another startup failure"));

            // then
            assertThat(failure.getCause()).isSameAs(cause);
            assertThat(failure.failure().primaryCode()).isEqualTo(AGENT_STARTUP_FAILED);
            assertThat(failure.failure().phase()).isEqualTo("startup");
            assertThat(cause.getSuppressed()).containsExactly(cleanupFailure);
            assertThat(coordinator.currentWire()).isSameAs(failed);
            assertThatDiagnosticMBean(server).isFailedWith(FAILED_ROLLBACK_INCOMPLETE)
                    .hasSnapshotEntry(JmxWireContract.FIELD_READINESS_SNAPSHOT_PRIMARY_FAILURE_CODE, AGENT_STARTUP_FAILED);
            assertThat(stickyFailure(coordinator, FAILED_ROLLBACK_INCOMPLETE).getCause()).isSameAs(cause);
            verify(runtime).close();
            assertThatCode(coordinator::close).doesNotThrowAnyException();
            assertThatDiagnosticMBean(server).isNotRegistered();
        }

        verify(runtime).close();
    }

    @Test
    void publication_failure_does_not_skip_startup_cleanup_or_replace_primary() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        PlatformExtensionReadiness readiness = spy(PlatformExtensionReadiness.forCurrentExtension());
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        ConfigurationException cause = new ConfigurationException("Invalid OTLP endpoint");
        IllegalStateException publicationFailure = new IllegalStateException("Failure publication failed");
        BootstrapCoordinator coordinator = coordinatorWithReadiness(server, readiness, runtime);

        try (coordinator) {
            coordinator.prepare(config());
            ExtensionReadinessWireSnapshot initializing = coordinator.currentWire();
            doThrow(publicationFailure).when(readiness).fail(anyString(), anyString());

            // when
            Throwable outcome = catchThrowable(() -> coordinator.onStartupFailure(cause));

            // then
            assertThat(outcome).isSameAs(publicationFailure);
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.currentWire()).isSameAs(initializing);
            assertThatDiagnosticMBean(server).isRegistered();
            assertThat(cause.getSuppressed()).containsExactly(publicationFailure);
            assertThat(stickyFailure(coordinator, AGENT_STARTUP_FAILED).getCause()).isSameAs(cause);
            verify(runtime).close();
        }

        verify(runtime).close();
        assertThatDiagnosticMBean(server).isNotRegistered();
    }

    @Test
    void previously_classified_failure_remains_sticky_when_startup_reports_its_wrapper() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        IllegalStateException cause = new IllegalStateException("Platform customizer failed");
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();

        try (coordinator) {
            coordinator.prepare(config());
            BootstrapException classified = catchBootstrapFailure(() -> coordinator.execute(CUSTOMIZATION_FAILED, () -> {
                throw cause;
            }), CUSTOMIZATION_FAILED);
            ExtensionReadinessWireSnapshot failed = coordinator.currentWire();

            // when
            coordinator.onStartupFailure(classified);

            // then
            assertThat(coordinator.currentWire()).isSameAs(failed);
            assertThat(stickyFailure(coordinator, CUSTOMIZATION_FAILED).getCause()).isSameAs(cause);
            assertThat(cause.getSuppressed()).isEmpty();
            assertThatDiagnosticMBean(server).isFailedWith(CUSTOMIZATION_FAILED);
            verify(runtime).close();
        }

        verify(runtime).close();
    }

    @Test
    void startup_failure_before_prepare_does_not_create_session_or_diagnostic_mbean() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();

        try (coordinator) {
            ExtensionReadinessWireSnapshot initializing = coordinator.currentWire();

            // when
            coordinator.onStartupFailure(new ConfigurationException("Failure before platform preparation"));

            // then
            assertThat(coordinator.state()).isEqualTo(BootstrapState.NEW);
            assertThat(coordinator.currentWire()).isSameAs(initializing);
            assertThatDiagnosticMBean(server).isNotRegistered();
            verifyNoInteractions(runtime);
        }
    }

    @Test
    void late_startup_failure_does_not_reopen_closed_coordinator() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        coordinator.prepare(config());
        coordinator.close();
        ExtensionReadinessWireSnapshot closed = coordinator.currentWire();

        // when
        coordinator.onStartupFailure(new ConfigurationException("Failure after close"));

        // then
        assertThat(coordinator.state()).isEqualTo(BootstrapState.CLOSED);
        assertThat(coordinator.currentWire()).isSameAs(closed);
        assertThatDiagnosticMBean(server).isNotRegistered();
        verify(runtime).close();
    }

    @Test
    void fatal_startup_failure_propagates_after_session_cleanup_and_failed_publication() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        LinkageError cause = new LinkageError("SDK linkage failed after preparation");
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();

        try (coordinator) {
            coordinator.prepare(config());

            // when
            Throwable outcome = catchThrowable(() -> coordinator.onStartupFailure(cause));

            // then
            assertThat(outcome).isSameAs(cause);
            assertThat(stickyFailure(coordinator, AGENT_STARTUP_FAILED).getCause()).isSameAs(cause);
            assertThatDiagnosticMBean(server).isFailedWith(AGENT_STARTUP_FAILED);
            verify(runtime).close();
        }

        verify(runtime).close();
    }

    @Test
    void fatal_cleanup_outcome_does_not_replace_original_startup_cause() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        ConfigurationException cause = new ConfigurationException("Invalid OTLP endpoint");
        OutOfMemoryError cleanupFailure = new OutOfMemoryError("Simulated cleanup memory failure");
        doThrow(cleanupFailure).when(runtime).close();
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();

        try (coordinator) {
            coordinator.prepare(config());

            // when
            Throwable outcome = catchThrowable(() -> coordinator.onStartupFailure(cause));

            // then
            assertThat(outcome).isSameAs(cleanupFailure);
            assertThat(BootstrapCleanupFailures.contains(cause, cleanupFailure)).isTrue();
            BootstrapException sticky = stickyFailure(coordinator, FAILED_ROLLBACK_INCOMPLETE);
            assertThat(sticky.getCause()).isSameAs(cause);
            assertThat(sticky.failure().primaryCode()).isEqualTo(AGENT_STARTUP_FAILED);
            assertThatDiagnosticMBean(server).isFailedWith(FAILED_ROLLBACK_INCOMPLETE);
            verify(runtime).close();
        }

        verify(runtime).close();
    }

    private static BootstrapException stickyFailure(BootstrapCoordinator coordinator, String code) {
        return catchBootstrapFailure(() -> coordinator.execute("after-startup-failure", () -> null), code);
    }

    private static PlatformScrubberFactory factory(ScrubbingRuntime runtime) {
        PlatformScrubberFactory factory = mock(PlatformScrubberFactory.class);
        doReturn(runtime).when(factory).buildRuntime(any());
        return factory;
    }

    private static BootstrapCoordinator coordinatorWithReadiness(MBeanServer server,
                                                                 PlatformExtensionReadiness readiness,
                                                                 ScrubbingRuntime runtime) {
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformTracingMBeanRegistrar registrar = PlatformTracingMBeanRegistrarTestSupport.create(
                server, readiness, agentInstanceId
        );
        return coordinator()
                .agentInstanceId(agentInstanceId)
                .registrar(registrar)
                .scrubberFactory(factory(runtime))
                .build();
    }
}
