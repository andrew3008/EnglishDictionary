package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorAssert.assertThatCoordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.acknowledgeScrubbingExcept;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.assertBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.catchBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.markCapabilitiesExcept;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.providerFor;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.registerForeignReadinessMBean;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.satisfyReadinessBarrier;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.DIAGNOSTIC_MBEAN_REGISTRATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.PLATFORM_SAMPLER_REPLACED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.TRANSPORT_STALE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.timer.Timer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.CapturingShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.ScrubbingAcknowledgement;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.ShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class BootstrapCoordinatorActivationTest {

    private static final String DUPLICATE_ACTIVATION = "DUPLICATE_ACTIVATION";

    @Test
    void repeated_activate_after_successful_activation_classified_as_DUPLICATE_ACTIVATION() {
        // given
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();

        try (BootstrapCoordinator coordinator = coordinator().hooks(hooks).build()) {
            AutoconfigureSession session = coordinator.prepare(config());
            String agentInstanceId = session.agentInstanceId().toString();
            satisfyReadinessBarrier(session);

            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                coordinator.activate(tracerProvider);
                assertThat(coordinator.currentWire().agentInstanceId()).isEqualTo(agentInstanceId);

                // when
                assertBootstrapFailure(() -> coordinator.activate(tracerProvider), DUPLICATE_ACTIVATION);

                // then
                assertThatCoordinator(coordinator).isActiveAndReady();
                hooks.runHook();
                assertThatCoordinator(coordinator).hasWireFailure(TRANSPORT_STALE);
                assertThat(coordinator.currentWire().agentInstanceId()).isEqualTo(agentInstanceId);
            }
        }
    }

    @Test
    void activation_rollback_failure_publishes_rollback_incomplete_and_preserves_original_cause() {
        // given
        IllegalStateException cleanupFailure = new IllegalStateException("simulated shutdown hook removal failure");
        ShutdownHookRegistry hooks = (threadName, action) -> () -> {
            throw cleanupFailure;
        };

        try (BootstrapCoordinator coordinator = coordinator().hooks(hooks).build()) {
            AutoconfigureSession session = coordinator.prepare(config());

            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                // when
                BootstrapException failure = catchBootstrapFailure(
                        () -> coordinator.activate(tracerProvider),
                        FAILED_ROLLBACK_INCOMPLETE
                );

                // then
                assertThat(failure.getCause().getSuppressed()).singleElement()
                        .satisfies(suppressed -> assertThat(suppressed.getCause()).isSameAs(cleanupFailure));
                assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
                assertThat(coordinator.currentWire().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
                assertThat(session.readiness().snapshot().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
            }
        }
    }

    @Test
    void failure_mid_activate_rolls_back_LIFO_and_keeps_diagnostic_MBean_with_FAILED() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            AutoconfigureSession session = coordinator.prepare(config());

            try (PlatformTracingMBeanRegistrar.RuntimeRegistration ignored =
                         session.jmxRegistrar().registerRuntimeControls(session.policy());
                 SdkTracerProvider tracerProvider = providerFor(session)) {
                // when
                assertBootstrapFailure(() -> coordinator.activate(tracerProvider), ACTIVATION_FAILED);

                // then
                assertThat(session.readiness().snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
                assertThat(session.readiness().snapshot().failureCode()).isEqualTo(ACTIVATION_FAILED);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ScrubbingAcknowledgement.class)
    void missing_scrubbing_acknowledgement_fails_activation_and_never_publishes_READY(
            ScrubbingAcknowledgement missing
    ) {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            AutoconfigureSession session = coordinator.prepare(config());
            markCapabilitiesExcept(session, EnumSet.of(
                    PlatformExtensionCapability.SANITIZER_INSTALLED,
                    PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED
            ));
            acknowledgeScrubbingExcept(session, EnumSet.of(missing));

            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                // when
                assertBootstrapFailure(() -> coordinator.activate(tracerProvider), ACTIVATION_FAILED);
            }

            // then
            assertThat(session.readiness().snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
            assertThat(session.readiness().snapshot().has(PlatformExtensionCapability.SANITIZER_INSTALLED)).isFalse();
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_FAILED);
            assertThat(coordinator.state()).isNotEqualTo(BootstrapState.ACTIVE);
        }
    }

    @Test
    void missing_non_sanitizer_capability_fails_activation_after_barrier() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            AutoconfigureSession session = coordinator.prepare(config());
            markCapabilitiesExcept(session, EnumSet.of(
                    PlatformExtensionCapability.PROPAGATION_HOOKS_INSTALLED,
                    PlatformExtensionCapability.SANITIZER_INSTALLED,
                    PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED
            ));
            acknowledgeScrubbingExcept(session, EnumSet.noneOf(ScrubbingAcknowledgement.class));

            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                // when
                assertBootstrapFailure(() -> coordinator.activate(tracerProvider), ACTIVATION_FAILED);
            }

            // then
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_FAILED);
            assertThat(session.readiness().snapshot().has(PlatformExtensionCapability.SANITIZER_INSTALLED)).isTrue();
            assertThat(session.readiness().snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        }
    }

    @Test
    void activation_prologue_failure_is_terminalized_and_published() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();

        try (BootstrapCoordinator coordinator = coordinator().server(server).build()) {
            coordinator.prepare(config());

            try (SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build()) {
                // when
                Throwable failure = catchThrowable(() -> coordinator.activate(tracerProvider));

                // then
                assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
                assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_FAILED);
                assertThat(failure).isInstanceOf(BootstrapException.class);
                assertThat(((BootstrapException) failure).failure().code()).isEqualTo(PLATFORM_SAMPLER_REPLACED);
                assertThat(coordinator.currentWire().failureCode()).isEqualTo(PLATFORM_SAMPLER_REPLACED);
                assertThatDiagnosticMBean(server).isFailedWith(PLATFORM_SAMPLER_REPLACED);
            }
        }
    }

    @Test
    void diagnostic_registration_failure_uses_owner_code_in_lifecycle_readiness_and_wire() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        registerForeignReadinessMBean(server);
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(
                server,
                AgentInstanceId.random()
        );

        try (BootstrapCoordinator coordinator = coordinator().registrar(registrar).build()) {
            // when
            assertBootstrapFailure(
                    () -> coordinator.prepare(config()),
                    DIAGNOSTIC_MBEAN_REGISTRATION_FAILED
            );

            // then
            assertThatCoordinator(coordinator).isTerminallyFailedWith(DIAGNOSTIC_MBEAN_REGISTRATION_FAILED);
            assertThat(registrar.extensionReadiness().snapshot().failureCode())
                    .isEqualTo(DIAGNOSTIC_MBEAN_REGISTRATION_FAILED);
            assertThatDiagnosticMBean(server).isOwnedBy(Timer.class);
        }
    }

    @Test
    void state_and_wire_never_visible_to_reader_in_inconsistent_combination_during_activate()
            throws InterruptedException {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);

            Set<String> observedPairs = new CopyOnWriteArraySet<>();
            AtomicBoolean pollerRunning = new AtomicBoolean(true);
            CountDownLatch firstObservation = new CountDownLatch(1);
            Thread poller = new Thread(() -> {
                while (pollerRunning.get()) {
                    observedPairs.add(coordinator.state().name() + "/" + coordinator.currentWire().lifecycle());
                    firstObservation.countDown();
                }
            }, "snapshot-poller");
            poller.setDaemon(true);
            poller.start();

            try {
                assertThat(firstObservation.await(5, TimeUnit.SECONDS))
                        .as("snapshot-poller made no observation within 5s")
                        .isTrue();

                // when
                try (SdkTracerProvider tracerProvider = providerFor(session)) {
                    coordinator.activate(tracerProvider);
                }

                // then
                assertThat(observedPairs)
                        .contains("PREPARED/INITIALIZING")
                        .isSubsetOf(
                                "PREPARED/INITIALIZING",
                                "ACTIVATING/INITIALIZING",
                                "ACTIVATING/READY",
                                "ACTIVE/READY"
                        );
                assertThatCoordinator(coordinator).isActiveAndReady();
            } finally {
                pollerRunning.set(false);
                poller.join(5_000);
            }

            assertThat(poller.isAlive()).as("snapshot-poller terminated").isFalse();
        }
    }
}
