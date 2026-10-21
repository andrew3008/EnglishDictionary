package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorAssert.assertThatCoordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_INITIALIZING;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.DIAGNOSTIC_MBEAN_REGISTRATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.assertBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.catchBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.recordingReadinessRegistration;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.registerForeignReadinessMBean;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;
import static space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessSnapshotAssertions.failureOf;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.timer.Timer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationFailureCode;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationException;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class BootstrapCoordinatorCustomizationTest {

    private static final String SPAN_PROCESSOR_INITIALIZATION_FAILED = "SPAN_PROCESSOR_INITIALIZATION_FAILED";
    private static final String EXPORTER_INITIALIZATION_FAILED = "EXPORTER_INITIALIZATION_FAILED";

    @Test
    void trace_pipeline_attestation_failure_before_prepare_publishes_diagnostic_mbean() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        IllegalStateException attestationFailure = new IllegalStateException("simulated trace pipeline attestation failure");
        BootstrapCoordinator coordinator = coordinator().server(server).agentInstanceId(agentInstanceId).build();

        try (coordinator) {
            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.execute(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED, () -> {
                        throw attestationFailure;
                    }),
                    SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED
            );

            // then
            assertThat(failure.getCause()).isSameAs(attestationFailure);
            assertThatCoordinator(coordinator)
                    .isTerminallyFailedWith(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED);
            assertThat(coordinator.currentWire().agentInstanceId()).isEqualTo(agentInstanceId.toString());
            assertThatDiagnosticMBean(server).isFailedWith(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED);
        }

        assertThatDiagnosticMBean(server).isNotRegistered();
    }

    @Test
    void linkage_error_before_prepare_is_published_before_original_error_propagates() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        NoClassDefFoundError linkageFailure = new NoClassDefFoundError("simulated customizer linkage failure");
        BootstrapCoordinator coordinator = coordinator().server(server).build();

        try (coordinator) {
            // when
            assertThatThrownBy(() -> coordinator.execute(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED, () -> {
                throw linkageFailure;
            })).isSameAs(linkageFailure);

            // then
            assertThatCoordinator(coordinator).isTerminallyFailedWith(VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name());
            assertThatDiagnosticMBean(server).isFailedWith(VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name());
        }

        assertThatDiagnosticMBean(server).isNotRegistered();
    }

    @Test
    void execute_preserves_SANITIZER_FOREIGN_SAFE_EXPORTER_and_fails_readiness() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();

        try (BootstrapCoordinator coordinator = coordinator().server(server).build()) {
            AutoconfigureSession session = coordinator.prepare(config());

            // when
            assertBootstrapFailure(
                    () -> coordinator.execute(EXPORTER_INITIALIZATION_FAILED, () -> {
                        throw new SanitizerBootstrapException(
                                SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER,
                                "Foreign SafeSpanExporter is not protected by the session scrubbing runtime "
                                        + "(space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter)");
                    }),
                    SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER
            );

            // then
            assertThat(session.readiness().snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
            assertThat(failureOf(session.readiness().snapshot()).effectiveCode())
                    .isEqualTo(SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER);
            assertThat(session.readiness().snapshot().has(PlatformExtensionCapability.SANITIZER_INSTALLED)).isFalse();
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_FAILED);
            assertThatDiagnosticMBean(server).isFailedWith(SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER);
        }
    }

    @Test
    void customization_failure_after_prepare_publishes_diagnostic_mbean_until_coordinator_close() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AgentInstanceId agentInstanceId = AgentInstanceId.parse("d3a9b6c2-1e47-4f05-8b2a-6c9e0f4d7a13");
        IllegalStateException customizerFailure = new IllegalStateException("simulated customizer failure");
        BootstrapCoordinator coordinator = coordinator().server(server).agentInstanceId(agentInstanceId).build();

        try (coordinator) {
            coordinator.prepare(config());

            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.execute(SPAN_PROCESSOR_INITIALIZATION_FAILED, () -> {
                        throw customizerFailure;
                    }),
                    SPAN_PROCESSOR_INITIALIZATION_FAILED
            );

            // then
            assertThat(failure.getCause()).isSameAs(customizerFailure);
            assertThat(customizerFailure.getSuppressed()).isEmpty();
            assertThatCoordinator(coordinator).isTerminallyFailedWith(SPAN_PROCESSOR_INITIALIZATION_FAILED);
            assertThat(coordinator.currentWire().agentInstanceId()).isEqualTo(agentInstanceId.toString());
            assertThatDiagnosticMBean(server)
                    .isFailedWith(SPAN_PROCESSOR_INITIALIZATION_FAILED)
                    .hasSnapshotEntry(
                            JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID,
                            agentInstanceId.toString()
                    );
        }

        assertThatDiagnosticMBean(server).isNotRegistered();
    }

    @Test
    void diagnostic_registration_failure_is_suppressed_on_prepare_cause() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        registerForeignReadinessMBean(server);

        try (BootstrapCoordinator coordinator = coordinator().server(server).build()) {
            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.prepare(config()),
                    DIAGNOSTIC_MBEAN_REGISTRATION_FAILED
            );

            // then
            assertThat(failure.getCause())
                    .isInstanceOf(JmxRegistrationException.class)
                    .hasMessageContaining("ownership collision");
            assertThat(failure.getCause().getSuppressed())
                    .singleElement()
                    .satisfies(suppressed -> assertThat(suppressed)
                            .isInstanceOf(JmxRegistrationException.class)
                            .hasMessageContaining("ownership collision"));
            assertThatCoordinator(coordinator).isTerminallyFailedWith(DIAGNOSTIC_MBEAN_REGISTRATION_FAILED);
            assertThatDiagnosticMBean(server).isOwnedBy(Timer.class);
        }
    }

    @Test
    void fatal_diagnostic_registration_is_suppressed_on_sticky_cause_and_propagated() {
        // given
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformTracingMBeanRegistrar registrar = spy(new PlatformTracingMBeanRegistrar(
                MBeanServerFactory.newMBeanServer(),
                agentInstanceId
        ));
        NoClassDefFoundError diagnosticFailure =
                new NoClassDefFoundError("Fatal diagnostic registration failure");
        //noinspection resource -- Mockito-заглушка, регистрация не создаётся
        doThrow(diagnosticFailure).when(registrar).registerDiagnosticReadiness(any());
        IllegalStateException customizationFailure = new IllegalStateException("Customizer failure");

        try (BootstrapCoordinator coordinator = coordinator()
                .agentInstanceId(agentInstanceId)
                .registrar(registrar)
                .build()) {
            // when
            assertThatThrownBy(() -> coordinator.execute(SPAN_PROCESSOR_INITIALIZATION_FAILED, () -> {
                throw customizationFailure;
            })).isSameAs(diagnosticFailure);

            // then
            BootstrapException sticky = catchBootstrapFailure(
                    () -> coordinator.prepare(config()),
                    SPAN_PROCESSOR_INITIALIZATION_FAILED
            );
            assertThat(sticky.getCause()).isSameAs(customizationFailure);
            assertThat(customizationFailure.getSuppressed()).containsExactly(diagnosticFailure);
            assertThat(diagnosticFailure.getSuppressed()).isEmpty();
            assertThatCoordinator(coordinator).isTerminallyFailedWith(SPAN_PROCESSOR_INITIALIZATION_FAILED);
        }
    }

    @Test
    void diagnostic_readiness_is_registered_during_prepare_and_not_again_on_customization_failure() {
        // given
        List<String> observations = new ArrayList<>();
        AtomicReference<BootstrapCoordinator> coordinatorReference = new AtomicReference<>();
        MBeanServer server = recordingReadinessRegistration(coordinatorReference, observations);
        IllegalStateException customizerFailure = new IllegalStateException("simulated customizer failure");
        BootstrapCoordinator coordinator = coordinator().server(server).build();
        coordinatorReference.set(coordinator);

        try (coordinator) {
            coordinator.prepare(config());

            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.execute(SPAN_PROCESSOR_INITIALIZATION_FAILED, () -> {
                        throw customizerFailure;
                    }),
                    SPAN_PROCESSOR_INITIALIZATION_FAILED
            );

            // then
            assertThat(observations).containsExactly("PREPARING/" + WIRE_INITIALIZING + "/");
            assertThat(failure.getCause()).isSameAs(customizerFailure);
            assertThatDiagnosticMBean(server).isRegistered();
        }
    }
}
