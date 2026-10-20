package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorAssert.assertThatCoordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.ROLLBACK_ESCALATION_EVIDENCE_GENERATION;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.TRANSPORT_STALE_EVIDENCE_GENERATION;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_READY;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.assertBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.invalidValidationLevelConfig;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.providerFor;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.satisfyReadinessBarrier;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.CONFIGURATION_INITIALIZATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.TRANSPORT_STALE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.CapturingShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BootstrapCoordinatorShutdownTest {

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void diagnostic_cleanup_failure_overrides_transport_stale_during_shutdown() throws Exception {
        // given
        IllegalArgumentException resourceCleanupFailure =
                new IllegalArgumentException("simulated shutdown hook removal failure");
        UnsupportedOperationException diagnosticFailure =
                new UnsupportedOperationException("simulated diagnostic readiness cleanup failure");
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry(resourceCleanupFailure);
        DiagnosticReadinessHandle diagnosticHandle = new DiagnosticReadinessHandle();

        try (BootstrapCoordinator coordinator = coordinator().hooks(hooks).diagnosticHandle(diagnosticHandle).build()) {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);
            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                coordinator.activate(tracerProvider);
            }

            try (AutoCloseable ignored = diagnosticHandle.replace(() -> {
                throw diagnosticFailure;
            })) {
                // when
                assertThatCode(hooks::runHook).doesNotThrowAnyException();

                // then
                assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
                assertThat(coordinator.currentWire().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
                assertThat(coordinator.currentWire().evidenceGeneration())
                        .isEqualTo(ROLLBACK_ESCALATION_EVIDENCE_GENERATION);
                assertThat(session.readiness().snapshot().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
                assertThat(logs.events())
                        .filteredOn(event -> event.eventCode() == AgentEventCode.BOOT_CLASSIFIED_FAILURE)
                        .singleElement()
                        .satisfies(event -> {
                            assertThat(event.fields())
                                    .containsEntry("phase", "shutdown")
                                    .containsEntry("resourceLabel", "shutdown-completion");
                            assertThat(event.throwableDigest())
                                    .contains(IllegalArgumentException.class.getName())
                                    .contains(UnsupportedOperationException.class.getName());
                        });
            }
        }
    }

    @Test
    void shutdown_publishes_transport_stale_before_closing_registered_resources() {
        // given
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();

        try (BootstrapCoordinator coordinator = coordinator().hooks(hooks).build()) {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);
            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                coordinator.activate(tracerProvider);
            }
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_READY);

            // when
            hooks.runHook();

            // then
            assertThatCoordinator(coordinator).hasWireFailure(TRANSPORT_STALE);
            assertThat(coordinator.currentWire().evidenceGeneration()).isEqualTo(TRANSPORT_STALE_EVIDENCE_GENERATION);
        }
    }

    @Test
    void close_without_session_publishes_diagnostic_cleanup_failure() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        UnsupportedOperationException diagnosticFailure =
                new UnsupportedOperationException("simulated diagnostic readiness cleanup failure");
        DiagnosticReadinessHandle diagnosticHandle = new DiagnosticReadinessHandle();

        try (BootstrapCoordinator coordinator = coordinator().server(server).diagnosticHandle(diagnosticHandle).build()) {
            assertBootstrapFailure(
                    () -> coordinator.prepare(invalidValidationLevelConfig()),
                    CONFIGURATION_INITIALIZATION_FAILED
            );

            try (AutoCloseable ignored = diagnosticHandle.replace(() -> {
                throw diagnosticFailure;
            })) {
                // when
                Throwable closeFailure = catchThrowable(coordinator::close);

                // then
                assertThat(closeFailure)
                        .isInstanceOf(IllegalStateException.class)
                        .hasRootCause(diagnosticFailure);
                assertThatCoordinator(coordinator).isTerminallyFailedWith(FAILED_ROLLBACK_INCOMPLETE);
                assertThatDiagnosticMBean(server).isFailedWith(FAILED_ROLLBACK_INCOMPLETE);
            }
        }
    }
}
