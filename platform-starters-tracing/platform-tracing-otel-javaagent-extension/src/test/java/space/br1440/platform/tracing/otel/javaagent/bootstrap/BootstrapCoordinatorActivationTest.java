package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorAssert.assertThatCoordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_READY;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.acknowledgeScrubbingExcept;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.assertBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.catchBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.failingRuntimeBatchRegistration;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.markCapabilitiesExcept;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.providerFor;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.registerForeignReadinessMBean;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.satisfyReadinessBarrier;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.DIAGNOSTIC_MBEAN_REGISTRATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.PLATFORM_SAMPLER_REPLACED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;
import static space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessSnapshotAssertions.failureOf;

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
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class BootstrapCoordinatorActivationTest {

    private static final String DUPLICATE_ACTIVATION = "DUPLICATE_ACTIVATION";
    private static final String JMX_BATCH_REGISTRATION_FAILED = "JMX_BATCH_REGISTRATION_FAILED";

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
                assertThat(hooks.registrationCount()).isOne();
                assertThat(hooks.threadName()).isEqualTo("platform-tracing-bootstrap-shutdown");
                hooks.runHook();
                assertThat(coordinator.state()).isEqualTo(BootstrapState.CLOSED);
                assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_READY);
                assertThat(coordinator.currentWire().failureCode()).isEmpty();
                assertThat(coordinator.currentWire().primaryFailureCode()).isEmpty();
                assertThat(coordinator.currentWire().agentInstanceId()).isEqualTo(agentInstanceId);
            }
        }
    }

    @Test
    void hook_registration_failure_is_terminalized_before_runtime_binding() {
        // given
        IllegalStateException registrationFailure =
                new IllegalStateException("Simulated shutdown hook registration failure");
        ShutdownHookRegistry hooks = (threadName, action) -> {
            throw registrationFailure;
        };
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        PlatformScrubberFactory scrubberFactory = mock(PlatformScrubberFactory.class);
        ScrubbingRuntime scrubbingRuntime = mock(ScrubbingRuntime.class);
        //noinspection resource — stubbing buildRuntime не получает ресурс; close() выполняет координатор
        doReturn(scrubbingRuntime).when(scrubberFactory).buildRuntime(any());

        try (BootstrapCoordinator coordinator = coordinator()
                .server(server)
                .hooks(hooks)
                .scrubberFactory(scrubberFactory)
                .build()) {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);

            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                // when
                BootstrapException failure = catchBootstrapFailure(
                        () -> coordinator.activate(tracerProvider),
                        ACTIVATION_FAILED
                );

                // then
                assertThat(failure.getCause()).isSameAs(registrationFailure);
                assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
                assertThat(coordinator.currentWire().failureCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(failureOf(session.readiness().snapshot()).effectiveCode()).isEqualTo(ACTIVATION_FAILED);
                assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
                verify(scrubbingRuntime).close();
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
                assertThat(failureOf(session.readiness().snapshot()).effectiveCode()).isEqualTo(ACTIVATION_FAILED);
            }
        }
    }

    @Test
    void failure_after_activation_guard_is_terminalized_and_published() {
        // given
        IllegalStateException prologueFailure = new IllegalStateException("Activation prologue failed");
        AutoconfigureSession session = mock(AutoconfigureSession.class);

        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        BootstrapReadinessJournal journal = new BootstrapReadinessJournal(
                lifecycle.monitor(),
                readiness,
                agentInstanceId
        );
        BootstrapTerminalTransitions transitions = new BootstrapTerminalTransitions(
                lifecycle,
                journal
        );
        BootstrapActivation activation = new BootstrapActivation(
                lifecycle,
                journal,
                transitions
        );
        lifecycle.beginPrepare();
        journal.publishInitializing();
        lifecycle.completePrepare(session);
        when(session.evidence()).thenThrow(prologueFailure);

        // when
        Throwable thrown = catchThrowable(() -> activation.activate(mock(SdkTracerProvider.class), () -> {
        }));

        // then
        assertThat(thrown).isInstanceOf(BootstrapException.class);
        BootstrapFailure failure = ((BootstrapException) thrown).failure();
        assertThat(failure.primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(failure.effectiveCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(failure.cause()).isSameAs(prologueFailure);
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        assertThat(journal.current().lifecycle()).isEqualTo(WIRE_FAILED);
        assertThat(journal.current().primaryFailureCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(journal.current().failureCode()).isEqualTo(ACTIVATION_FAILED);
    }

    @Test
    void runtime_batch_failure_uses_owner_code_in_lifecycle_readiness_and_wire() {
        // given
        MBeanServer server = failingRuntimeBatchRegistration();

        try (BootstrapCoordinator coordinator = coordinator().server(server).build()) {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);

            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                // when
                BootstrapException failure = catchBootstrapFailure(
                        () -> coordinator.activate(tracerProvider),
                        JMX_BATCH_REGISTRATION_FAILED
                );

                // then
                assertThat(failure.failure().primaryCode()).isEqualTo(JMX_BATCH_REGISTRATION_FAILED);
                assertThat(failure.failure().effectiveCode()).isEqualTo(JMX_BATCH_REGISTRATION_FAILED);
                assertThat(failureOf(session.readiness().snapshot()).primaryCode())
                        .isEqualTo(JMX_BATCH_REGISTRATION_FAILED);
                assertThat(failureOf(session.readiness().snapshot()).effectiveCode())
                        .isEqualTo(JMX_BATCH_REGISTRATION_FAILED);
                assertThat(coordinator.currentWire().primaryFailureCode()).isEqualTo(JMX_BATCH_REGISTRATION_FAILED);
                assertThat(coordinator.currentWire().failureCode()).isEqualTo(JMX_BATCH_REGISTRATION_FAILED);
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
                assertThat(((BootstrapException) failure).failure().effectiveCode())
                        .isEqualTo(PLATFORM_SAMPLER_REPLACED);
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
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.prepare(config()),
                    DIAGNOSTIC_MBEAN_REGISTRATION_FAILED
            );

            // then
            assertThat(failure.failure().primaryCode()).isEqualTo(DIAGNOSTIC_MBEAN_REGISTRATION_FAILED);
            assertThat(failure.failure().effectiveCode()).isEqualTo(DIAGNOSTIC_MBEAN_REGISTRATION_FAILED);
            assertThatCoordinator(coordinator).isTerminallyFailedWith(DIAGNOSTIC_MBEAN_REGISTRATION_FAILED);
            assertThat(coordinator.currentWire().primaryFailureCode())
                    .isEqualTo(DIAGNOSTIC_MBEAN_REGISTRATION_FAILED);
            assertThat(failureOf(registrar.extensionReadiness().snapshot()).effectiveCode())
                    .isEqualTo(DIAGNOSTIC_MBEAN_REGISTRATION_FAILED);
            assertThat(failureOf(registrar.extensionReadiness().snapshot()).primaryCode())
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
