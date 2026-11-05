package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.assertBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.providerFor;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.satisfyReadinessBarrier;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailureCodes.AGENT_STARTUP_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailure;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.CustomizationStep;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BootstrapCoordinatorStartupAdmissionTest {

    private static final long TIMEOUT_SECONDS = 10;
    private static final CustomizationStep STEP = CustomizationStep.SPAN_PROCESSOR_INITIALIZATION;

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdownExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    // ACTIVATING наблюдаем только под монитором lifecycle; допуск из него закреплён BootstrapLifecycleAdmissionMatrixTest
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = BootstrapState.class, names = {"NEW", "ACTIVE", "FAILED", "CLOSED"})
    void startup_failure_outside_prepared_leaves_coordinator_untouched(BootstrapState initialState) {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        ConfigurationException cause = new ConfigurationException("Late startup failure");

        try {
            enterState(coordinator, initialState);
            clearInvocations(runtime);
            ExtensionReadinessWireSnapshot wire = coordinator.currentWire();
            BootstrapFailure failure = coordinator.diagnosticSnapshot().failure();
            boolean diagnosticRegistered = server.isRegistered(DomainObjectNames.EXTENSION_READINESS);
            int loggedEvents = logs.events().size();

            // when
            Throwable outcome = catchThrowable(() -> coordinator.onStartupFailure(cause));

            // then
            assertThat(outcome).isNull();
            assertThat(coordinator.state()).isEqualTo(initialState);
            assertThat(coordinator.currentWire()).isSameAs(wire);
            assertThat(coordinator.diagnosticSnapshot().failure()).isEqualTo(failure);
            assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isEqualTo(diagnosticRegistered);
            assertThat(logs.events()).hasSize(loggedEvents);
            assertThat(cause.getSuppressed()).isEmpty();
            verifyNoInteractions(runtime);
        } finally {
            closeQuietly(coordinator);
        }
    }

    @Test
    void startup_failure_from_prepared_is_recorded_released_and_logged_once() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        ConfigurationException cause = new ConfigurationException("Agent startup failed");

        try {
            coordinator.prepare(config());

            // when
            Throwable outcome = catchThrowable(() -> coordinator.onStartupFailure(cause));

            // then
            assertThat(outcome).isNull();
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            BootstrapFailure failure = coordinator.diagnosticSnapshot().failure();
            assertThat(failure.primaryCode()).isEqualTo(AGENT_STARTUP_FAILED);
            assertThat(failure.cause()).isSameAs(cause);
            assertThatDiagnosticMBean(server).isFailedWith(AGENT_STARTUP_FAILED);
            assertThat(phaseFailures()).singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("phase", "startup"));
            verify(runtime).close();
        } finally {
            closeQuietly(coordinator);
        }
    }

    @Test
    void startup_failure_during_preparation_is_not_admitted() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        AtomicReference<BootstrapCoordinator> coordinatorReference = new AtomicReference<>();
        AtomicReference<BootstrapState> observedState = new AtomicReference<>();
        AtomicReference<Throwable> nestedOutcome = new AtomicReference<>();
        ConfigurationException cause = new ConfigurationException("Startup failure during preparation");
        BootstrapCoordinator coordinator = coordinator()
                .server(server)
                .scrubberFactory(factory(runtime))
                .verifier(extensionVersion -> {
                    BootstrapCoordinator preparing = coordinatorReference.get();
                    observedState.set(preparing.state());
                    nestedOutcome.set(catchThrowable(() -> preparing.onStartupFailure(cause)));
                    return RuntimeEvidenceTestFixtures.evidence(extensionVersion);
                })
                .build();
        coordinatorReference.set(coordinator);

        try {
            // when
            coordinator.prepare(config());

            // then
            assertThat(observedState).hasValue(BootstrapState.PREPARING);
            assertThat(nestedOutcome.get()).isNull();
            assertThat(coordinator.state()).isEqualTo(BootstrapState.PREPARED);
            assertThat(coordinator.diagnosticSnapshot().failure()).isNull();
            assertThatDiagnosticMBean(server).isRegistered();
            assertThat(phaseFailures()).isEmpty();
            verifyNoInteractions(runtime);
        } finally {
            closeQuietly(coordinator);
        }
    }

    @Test
    void concurrent_repeated_startup_failure_is_not_admitted_while_first_settlement_releases_session()
            throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        CountDownLatch releaseStarted = new CountDownLatch(1);
        CountDownLatch releaseAllowed = new CountDownLatch(1);
        blockClose(runtime, releaseStarted, releaseAllowed);
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        ConfigurationException firstCause = new ConfigurationException("First startup failure");
        ConfigurationException secondCause = new ConfigurationException("Repeated startup failure");

        try {
            coordinator.prepare(config());
            Future<?> first = executor.submit(() -> coordinator.onStartupFailure(firstCause));
            assertThat(releaseStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            // when
            Throwable secondOutcome = catchThrowable(() -> coordinator.onStartupFailure(secondCause));
            boolean firstCompletedBeforeRelease = first.isDone();
            releaseAllowed.countDown();
            first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // then
            assertThat(secondOutcome).isNull();
            assertThat(firstCompletedBeforeRelease).isFalse();
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.diagnosticSnapshot().failure().cause()).isSameAs(firstCause);
            assertThat(secondCause.getSuppressed()).isEmpty();
            assertThatDiagnosticMBean(server).isFailedWith(AGENT_STARTUP_FAILED);
            assertThat(phaseFailures()).hasSize(1);
            verify(runtime).close();
        } finally {
            releaseAllowed.countDown();
            closeQuietly(coordinator);
        }
    }

    @Test
    void startup_failure_during_close_is_not_admitted() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        CountDownLatch releaseStarted = new CountDownLatch(1);
        CountDownLatch releaseAllowed = new CountDownLatch(1);
        blockClose(runtime, releaseStarted, releaseAllowed);
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        ConfigurationException cause = new ConfigurationException("Startup failure during close");

        try {
            coordinator.prepare(config());
            Future<?> close = executor.submit(coordinator::close);
            assertThat(releaseStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            BootstrapState stateDuringClose = coordinator.state();

            // when
            Throwable outcome = catchThrowable(() -> coordinator.onStartupFailure(cause));
            boolean closeCompletedBeforeRelease = close.isDone();
            releaseAllowed.countDown();
            close.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // then
            assertThat(stateDuringClose).isEqualTo(BootstrapState.CLOSING);
            assertThat(outcome).isNull();
            assertThat(closeCompletedBeforeRelease).isFalse();
            assertThat(coordinator.state()).isEqualTo(BootstrapState.CLOSED);
            assertThat(coordinator.diagnosticSnapshot().failure()).isNull();
            assertThatDiagnosticMBean(server).isNotRegistered();
            assertThat(phaseFailures()).isEmpty();
            verify(runtime).close();
        } finally {
            releaseAllowed.countDown();
            closeQuietly(coordinator);
        }
    }

    @Test
    void close_during_startup_settlement_does_not_release_claimed_session_or_reregister_diagnostics()
            throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        CountDownLatch releaseStarted = new CountDownLatch(1);
        CountDownLatch releaseAllowed = new CountDownLatch(1);
        blockClose(runtime, releaseStarted, releaseAllowed);
        BootstrapCoordinator coordinator = coordinator().server(server).scrubberFactory(factory(runtime)).build();
        ConfigurationException cause = new ConfigurationException("Agent startup failed");

        try {
            coordinator.prepare(config());
            Future<?> settlement = executor.submit(() -> coordinator.onStartupFailure(cause));
            assertThat(releaseStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            // when
            Future<?> close = executor.submit(coordinator::close);
            close.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            boolean settlementCompletedBeforeRelease = settlement.isDone();
            releaseAllowed.countDown();
            settlement.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // then
            assertThat(settlementCompletedBeforeRelease).isFalse();
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.diagnosticSnapshot().failure().cause()).isSameAs(cause);
            assertThatDiagnosticMBean(server).isNotRegistered();
            assertThat(phaseFailures()).hasSize(1);
            verify(runtime).close();
        } finally {
            releaseAllowed.countDown();
            closeQuietly(coordinator);
        }
    }

    private static void enterState(BootstrapCoordinator coordinator, BootstrapState state) {
        switch (state) {
            case NEW -> {
            }
            case ACTIVE -> {
                AutoconfigureSession session = coordinator.prepare(config());
                satisfyReadinessBarrier(session);
                try (SdkTracerProvider tracerProvider = providerFor(session)) {
                    coordinator.activate(tracerProvider);
                }
            }
            case FAILED -> {
                coordinator.prepare(config());
                assertBootstrapFailure(() -> coordinator.execute(STEP, () -> {
                    throw new IllegalStateException("Platform customizer failed");
                }), STEP.failureCode());
            }
            case CLOSED -> {
                coordinator.prepare(config());
                coordinator.close();
            }
            default -> throw new IllegalArgumentException("Unsupported initial state " + state);
        }

        assertThat(coordinator.state()).isEqualTo(state);
    }

    private List<CapturedLogEvent> phaseFailures() {
        return logs.events().stream()
                .filter(event -> BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED.name().equals(event.eventName()))
                .toList();
    }

    private static void blockClose(ScrubbingRuntime runtime,
                                   CountDownLatch releaseStarted,
                                   CountDownLatch releaseAllowed) {
        doAnswer(invocation -> {
            releaseStarted.countDown();
            assertThat(releaseAllowed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(runtime).close();
    }

    private static PlatformScrubberFactory factory(ScrubbingRuntime runtime) {
        PlatformScrubberFactory factory = mock(PlatformScrubberFactory.class);
        //noinspection resource -- настройка Mockito не создаёт ресурс; runtime закрывает координатор
        doReturn(runtime).when(factory).buildRuntime(any());
        return factory;
    }

    private static void closeQuietly(BootstrapCoordinator coordinator) {
        try {
            coordinator.close();
        } catch (RuntimeException | Error ignored) {
            // повторное закрытие после проверки не должно подменять отказ теста
        }
    }
}
