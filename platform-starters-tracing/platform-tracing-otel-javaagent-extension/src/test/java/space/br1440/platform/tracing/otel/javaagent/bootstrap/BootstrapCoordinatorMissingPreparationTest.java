package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorAssert.assertThatCoordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.assertBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.catchBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.registerForeignReadinessMBean;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.PLATFORM_ACP_NOT_EXECUTED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.timer.Timer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationException;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class BootstrapCoordinatorMissingPreparationTest {

    @Test
    void activate_without_prior_prepare_publishes_diagnostic_mbean_until_coordinator_close() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AgentInstanceId agentInstanceId = AgentInstanceId.parse("7c0f4e1a-3b52-4d8e-9a61-2f5d8c7b9e04");
        BootstrapCoordinator coordinator = coordinator().server(server).agentInstanceId(agentInstanceId).build();

        try (coordinator; SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build()) {
            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.activate(tracerProvider),
                    PLATFORM_ACP_NOT_EXECUTED
            );

            // then
            assertThat(failure.failure().primaryCode()).isEqualTo(PLATFORM_ACP_NOT_EXECUTED);
            assertThat(failure.failure().effectiveCode()).isEqualTo(PLATFORM_ACP_NOT_EXECUTED);
            assertThat(failure.getCause().getSuppressed()).isEmpty();
            assertThatCoordinator(coordinator).isTerminallyFailedWith(PLATFORM_ACP_NOT_EXECUTED);
            assertThat(coordinator.currentWire().primaryFailureCode()).isEqualTo(PLATFORM_ACP_NOT_EXECUTED);
            assertThat(coordinator.currentWire().agentInstanceId()).isEqualTo(agentInstanceId.toString());
            assertThat(coordinator.currentWire().batchId()).isEmpty();
            assertThat(coordinator.currentWire().evidenceGeneration()).isZero();
            assertThatDiagnosticMBean(server)
                    .isFailedWith(PLATFORM_ACP_NOT_EXECUTED)
                    .hasSnapshotEntry(
                            JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID,
                            agentInstanceId.toString()
                    );
        }

        assertThatDiagnosticMBean(server).isNotRegistered();
    }

    @Test
    void repeated_activate_without_prior_prepare_keeps_single_diagnostic_registration() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();

        try (BootstrapCoordinator coordinator = coordinator().server(server).build();
             SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build()) {
            BootstrapException first = catchBootstrapFailure(
                    () -> coordinator.activate(tracerProvider),
                    PLATFORM_ACP_NOT_EXECUTED
            );
            int registeredMBeans = server.getMBeanCount();

            // when
            BootstrapException repeated = catchBootstrapFailure(
                    () -> coordinator.activate(tracerProvider),
                    PLATFORM_ACP_NOT_EXECUTED
            );

            // then
            assertThat(repeated.getCause()).isSameAs(first.getCause());
            assertThat(repeated.getCause().getSuppressed()).isEmpty();
            assertThat(server.getMBeanCount()).isEqualTo(registeredMBeans);
            assertThatDiagnosticMBean(server).isRegistered();
            assertThat(coordinator.currentWire().failureCode()).isEqualTo(PLATFORM_ACP_NOT_EXECUTED);
        }
    }

    @Test
    void diagnostic_registration_failure_is_suppressed_on_missing_preparation_cause() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        registerForeignReadinessMBean(server);

        try (BootstrapCoordinator coordinator = coordinator().server(server).build();
             SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build()) {
            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.activate(tracerProvider),
                    PLATFORM_ACP_NOT_EXECUTED
            );

            // then
            assertThat(failure.getCause().getSuppressed()).singleElement()
                    .isInstanceOf(JmxRegistrationException.class)
                    .satisfies(suppressed -> assertThat(suppressed).hasMessageContaining("ownership collision"));
            assertThatCoordinator(coordinator).isTerminallyFailedWith(PLATFORM_ACP_NOT_EXECUTED);
            assertThatDiagnosticMBean(server).isOwnedBy(Timer.class);
        }
    }

    @Test
    void fatal_diagnostic_registration_is_suppressed_on_missing_preparation_cause_and_propagated() {
        // given
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformTracingMBeanRegistrar registrar = spy(new PlatformTracingMBeanRegistrar(
                MBeanServerFactory.newMBeanServer(),
                agentInstanceId
        ));
        NoClassDefFoundError diagnosticFailure = new NoClassDefFoundError("Fatal diagnostic registration failure");
        //noinspection resource -- Mockito-заглушка, регистрация не создаётся
        doThrow(diagnosticFailure).when(registrar).registerDiagnosticReadiness(any());

        try (BootstrapCoordinator coordinator = coordinator()
                .agentInstanceId(agentInstanceId)
                .registrar(registrar)
                .build();
             SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build()) {
            // when
            assertThatThrownBy(() -> coordinator.activate(tracerProvider)).isSameAs(diagnosticFailure);

            // then
            BootstrapException sticky = catchBootstrapFailure(
                    () -> coordinator.prepare(config()),
                    PLATFORM_ACP_NOT_EXECUTED
            );
            assertThat(sticky.getCause().getSuppressed()).containsExactly(diagnosticFailure);
            assertThat(diagnosticFailure.getSuppressed()).isEmpty();
            assertThatCoordinator(coordinator).isTerminallyFailedWith(PLATFORM_ACP_NOT_EXECUTED);
        }
    }

    @Test
    void terminal_coordinator_repeats_same_code_for_any_subsequent_operation() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build();
             SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build()) {
            assertBootstrapFailure(() -> coordinator.activate(tracerProvider), PLATFORM_ACP_NOT_EXECUTED);

            // when/then
            assertBootstrapFailure(() -> coordinator.prepare(config()), PLATFORM_ACP_NOT_EXECUTED);
            assertBootstrapFailure(() -> coordinator.activate(tracerProvider), PLATFORM_ACP_NOT_EXECUTED);
            assertBootstrapFailure(
                    () -> coordinator.execute("SPAN_PROCESSOR_INITIALIZATION_FAILED", () -> null),
                    PLATFORM_ACP_NOT_EXECUTED
            );
        }
    }
}
