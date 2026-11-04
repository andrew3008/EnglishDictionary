package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.catchAdmissionRejection;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.catchBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.providerFor;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.satisfyReadinessBarrier;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.DUPLICATE_ACTIVATION;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.SECOND_SDK_BUILD;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxAssessorTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingRuntime;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingTestSupport;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BootstrapLoggingOutsideLifecycleMonitorTest {

    private static final long MONITOR_PROBE_TIMEOUT_MILLIS = 2_000;
    private static final CustomizationStep STEP = CustomizationStep.SPAN_PROCESSOR_INITIALIZATION;
    private static final String PHASE_FAILED = BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED.name();
    private static final String OPERATION_REJECTED = BootstrapLogEvent.BOOTSTRAP_OPERATION_REJECTED.name();
    private static final String REMOTE_JMX_DENIED = BootstrapLogEvent.REMOTE_JMX_MUTATION_REGISTRATION_DENIED.name();
    private static final String EXTENSION_INSTALLED = BootstrapLogEvent.EXTENSION_INSTALLED.name();

    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdownProbe() throws InterruptedException {
        probeExecutor.shutdownNow();
        assertThat(probeExecutor.awaitTermination(MONITOR_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void customization_settlement_and_escalation_are_logged_outside_lifecycle_monitor() throws Exception {
        // given
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        doThrow(new IllegalStateException("Scrubbing cleanup failed")).when(runtime).close();
        BootstrapCoordinator coordinator = coordinator().scrubberFactory(factory(runtime)).build();
        MonitorProbingStream sink = new MonitorProbingStream(coordinator, probeExecutor);

        try (coordinator) {
            coordinator.prepare(config());

            // when
            try (AutoCloseable ignored = install(sink)) {
                catchBootstrapFailure(() -> coordinator.execute(STEP, () -> {
                    throw new IllegalStateException("Platform customizer failed");
                }), FAILED_ROLLBACK_INCOMPLETE);
            }

            // then
            assertThat(sink.violations()).isEmpty();
            assertThat(sink.eventsContaining(PHASE_FAILED)).hasSize(2);
        }
    }

    @Test
    void admission_rejections_are_logged_outside_lifecycle_monitor() throws Exception {
        // given
        BootstrapCoordinator coordinator = coordinator().build();
        MonitorProbingStream sink = new MonitorProbingStream(coordinator, probeExecutor);

        try (coordinator) {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);

            // when
            try (SdkTracerProvider tracerProvider = providerFor(session);
                 AutoCloseable ignored = install(sink)) {
                catchAdmissionRejection(() -> coordinator.prepare(config()), SECOND_SDK_BUILD);
                coordinator.activate(tracerProvider);
                catchAdmissionRejection(() -> coordinator.activate(tracerProvider), DUPLICATE_ACTIVATION);
            }

            // then
            assertThat(sink.violations()).isEmpty();
            assertThat(sink.eventsContaining(OPERATION_REJECTED)).hasSize(2);
            assertThat(sink.eventsContaining(EXTENSION_INSTALLED)).hasSize(1);
        }
    }

    @Test
    void close_failure_is_logged_outside_lifecycle_monitor() throws Exception {
        // given
        ScrubbingRuntime runtime = mock(ScrubbingRuntime.class);
        doThrow(new IllegalStateException("Scrubbing cleanup failed")).when(runtime).close();
        BootstrapCoordinator coordinator = coordinator().scrubberFactory(factory(runtime)).build();
        MonitorProbingStream sink = new MonitorProbingStream(coordinator, probeExecutor);
        AutoconfigureSession session = coordinator.prepare(config());
        satisfyReadinessBarrier(session);
        try (SdkTracerProvider tracerProvider = providerFor(session)) {
            coordinator.activate(tracerProvider);
        }

        // when
        Throwable closeOutcome;
        try (AutoCloseable ignored = install(sink)) {
            closeOutcome = catchThrowable(coordinator::close);
        }

        // then
        assertThat(closeOutcome).isNotNull();
        assertThat(sink.violations()).isEmpty();
        assertThat(sink.eventsContaining(PHASE_FAILED)).hasSize(1);
    }

    @Test
    void remote_jmx_denial_is_logged_outside_lifecycle_monitor() throws Exception {
        // given
        BootstrapCoordinator coordinator = coordinator()
                .assessor(RemoteJmxAssessorTestFixtures.managementAgentAssessor())
                .build();
        MonitorProbingStream sink = new MonitorProbingStream(coordinator, probeExecutor);

        try (coordinator) {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);

            // when
            Throwable activationOutcome;
            try (SdkTracerProvider tracerProvider = providerFor(session);
                 AutoCloseable ignored = install(sink)) {
                activationOutcome = catchThrowable(() -> coordinator.activate(tracerProvider));
            }

            // then
            assertThat(activationOutcome).isInstanceOf(BootstrapException.class);
            assertThat(sink.violations()).isEmpty();
            assertThat(sink.eventsContaining(REMOTE_JMX_DENIED)).hasSize(1);
            assertThat(sink.eventsContaining(PHASE_FAILED)).hasSize(1);
        }
    }

    @Test
    void reentrant_activation_from_hook_logs_only_outer_failure_outside_lifecycle_monitor() throws Exception {
        // given
        AtomicReference<BootstrapCoordinator> coordinatorReference = new AtomicReference<>();
        AtomicReference<SdkTracerProvider> providerReference = new AtomicReference<>();
        BootstrapCoordinator coordinator = coordinator()
                .hooks((threadName, action) -> coordinatorReference.get().activate(providerReference.get()))
                .build();
        coordinatorReference.set(coordinator);
        MonitorProbingStream sink = new MonitorProbingStream(coordinator, probeExecutor);

        try (coordinator) {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);

            // when
            try (SdkTracerProvider tracerProvider = providerFor(session);
                 AutoCloseable ignored = install(sink)) {
                providerReference.set(tracerProvider);
                catchBootstrapFailure(() -> coordinator.activate(tracerProvider), DUPLICATE_ACTIVATION);
            }

            // then
            assertThat(sink.violations()).isEmpty();
            assertThat(sink.eventsContaining(OPERATION_REJECTED)).isEmpty();
            assertThat(sink.eventsContaining(PHASE_FAILED)).hasSize(1);
        }
    }

    @Test
    void startup_settlement_is_logged_outside_lifecycle_monitor() throws Exception {
        // given
        BootstrapCoordinator coordinator = coordinator().build();
        MonitorProbingStream sink = new MonitorProbingStream(coordinator, probeExecutor);

        try (coordinator) {
            coordinator.prepare(config());

            // when
            try (AutoCloseable ignored = install(sink)) {
                coordinator.onStartupFailure(new ConfigurationException("Agent startup failed"));
            }

            // then
            assertThat(sink.violations()).isEmpty();
            assertThat(sink.eventsContaining(PHASE_FAILED)).hasSize(1);
        }
    }

    private static AutoCloseable install(MonitorProbingStream sink) {
        AgentLoggingTestSupport.reset();
        AutoCloseable installation = AgentLoggingTestSupport.install(sink);
        AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.INFO);
        return installation;
    }

    private static PlatformScrubberFactory factory(ScrubbingRuntime runtime) {
        PlatformScrubberFactory factory = mock(PlatformScrubberFactory.class);
        //noinspection resource -- настройка Mockito не создаёт ресурс; runtime закрывает координатор
        doReturn(runtime).when(factory).buildRuntime(any());
        return factory;
    }

    /**
     * Удержание монитора lifecycle пишущим потоком проверяется извне: чтение состояния координатора из
     * отдельного потока блокируется, пока монитор занят.
     */
    private static final class MonitorProbingStream extends OutputStream {

        private final BootstrapCoordinator coordinator;
        private final ExecutorService probeExecutor;
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final List<String> violations = new CopyOnWriteArrayList<>();

        private MonitorProbingStream(BootstrapCoordinator coordinator, ExecutorService probeExecutor) {
            this.coordinator = coordinator;
            this.probeExecutor = probeExecutor;
        }

        @Override
        public void write(int value) {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(@Nonnull byte[] payload) {
            write(payload, 0, payload.length);
        }

        @Override
        public void write(@Nonnull byte[] payload, int offset, int length) {
            String rendered = new String(payload, offset, length, StandardCharsets.UTF_8);
            events.add(rendered);
            Future<BootstrapState> probe = probeExecutor.submit(coordinator::state);
            try {
                probe.get(MONITOR_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException monitorHeld) {
                violations.add(rendered);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                violations.add(rendered);
            } catch (ExecutionException probeFailure) {
                throw new IllegalStateException("Lifecycle monitor probe failed", probeFailure);
            }
        }

        private List<String> eventsContaining(String eventName) {
            return events.stream()
                    .filter(event -> event.contains(eventName))
                    .toList();
        }

        private List<String> violations() {
            return violations;
        }
    }
}
