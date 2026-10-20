package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorAssert.assertThatCoordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.expectBootstrapFailure;
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

import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class BootstrapCoordinatorMissingPreparationTest {

    @Test
    void activate_without_prior_prepare_classified_as_PLATFORM_ACP_NOT_EXECUTED() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build();
             SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build()) {
            // when/then
            expectBootstrapFailure(() -> coordinator.activate(tracerProvider), PLATFORM_ACP_NOT_EXECUTED);
        }
    }

    @Test
    void activate_without_prior_prepare_publishes_diagnostic_mbean_until_coordinator_close() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AgentInstanceId agentInstanceId = AgentInstanceId.parse("7c0f4e1a-3b52-4d8e-9a61-2f5d8c7b9e04");
        BootstrapCoordinator coordinator = coordinator().server(server).agentInstanceId(agentInstanceId).build();

        try (coordinator; SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build()) {
            // when
            BootstrapException failure = expectBootstrapFailure(
                    () -> coordinator.activate(tracerProvider),
                    PLATFORM_ACP_NOT_EXECUTED
            );

            // then
            assertThat(failure.getCause().getSuppressed()).isEmpty();
            assertThatCoordinator(coordinator).isTerminallyFailedWith(PLATFORM_ACP_NOT_EXECUTED);
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
            BootstrapException first = expectBootstrapFailure(
                    () -> coordinator.activate(tracerProvider),
                    PLATFORM_ACP_NOT_EXECUTED
            );
            int registeredMBeans = server.getMBeanCount();

            // when
            BootstrapException repeated = expectBootstrapFailure(
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
            BootstrapException failure = expectBootstrapFailure(
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
    void terminal_coordinator_repeats_same_code_for_any_subsequent_operation() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build();
             SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build()) {
            expectBootstrapFailure(() -> coordinator.activate(tracerProvider), PLATFORM_ACP_NOT_EXECUTED);

            // when/then
            expectBootstrapFailure(() -> coordinator.prepare(config()), PLATFORM_ACP_NOT_EXECUTED);
            expectBootstrapFailure(() -> coordinator.activate(tracerProvider), PLATFORM_ACP_NOT_EXECUTED);
            expectBootstrapFailure(
                    () -> coordinator.execute("SPAN_PROCESSOR_INITIALIZATION_FAILED", () -> null),
                    PLATFORM_ACP_NOT_EXECUTED
            );
        }
    }
}
