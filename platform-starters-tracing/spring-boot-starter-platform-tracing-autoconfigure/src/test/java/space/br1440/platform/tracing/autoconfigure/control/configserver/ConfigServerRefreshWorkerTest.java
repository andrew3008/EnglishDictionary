package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentStartupVerifier;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxReadAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ConfigServerRefreshWorkerTest {

    private static final long BOUND_SECONDS = 5L;

    @Test
    void closeWaitsForInFlightRefreshAndTerminatesWorkerThread() throws Exception {
        // given
        ConfigServerRefreshWorker worker = new ConfigServerRefreshWorker(Duration.ofSeconds(BOUND_SECONDS));
        InFlightTask task = InFlightTask.submitTo(worker);

        // when
        Thread closer = Thread.ofPlatform().start(worker::close);
        awaitState(closer, Thread.State.TIMED_WAITING);
        task.release();
        closer.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));

        // then
        assertThat(closer.isAlive()).isFalse();
        assertThat(task.completed()).isTrue();
        assertWorkerThreadTerminated(task.thread());
    }

    @Test
    void closeReturnsAfterTimeoutAndWarnsWhileRefreshIsStillRunning() throws Exception {
        // given
        ConfigServerRefreshWorker worker = new ConfigServerRefreshWorker(Duration.ofMillis(50));
        InFlightTask task = InFlightTask.submitTo(worker);
        ListAppender<ILoggingEvent> appender = attachAppender();

        try {
            // when
            worker.close();

            // then
            assertThat(task.completed()).isFalse();
            assertThatThrownBy(() -> worker.executor().execute(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(appender.list)
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage())
                                .contains("50 ms", ConfigServerRefreshWorker.THREAD_NAME);
                    });
        } finally {
            detachAppender(appender);
            task.release();
        }
        assertWorkerThreadTerminated(task.thread());
    }

    @Test
    void interruptedCloseRestoresInterruptFlag() throws Exception {
        // given
        ConfigServerRefreshWorker worker = new ConfigServerRefreshWorker(Duration.ofSeconds(BOUND_SECONDS));
        InFlightTask task = InFlightTask.submitTo(worker);

        try {
            // when
            Thread.currentThread().interrupt();
            worker.close();

            // then
            assertThat(Thread.interrupted()).isTrue();
            assertThat(task.completed()).isFalse();
        } finally {
            task.release();
        }
        assertWorkerThreadTerminated(task.thread());
    }

    @Test
    void contextCloseShutsDownRefreshWorkerThread() throws Exception {
        // given
        AtomicReference<ConfigServerRefreshWorker> workerRef = new AtomicReference<>();
        AtomicReference<Thread> workerThread = new AtomicReference<>();

        try (FakeAgentPlatformJmxPlane ignored =
                     FakeAgentPlatformJmxPlane.compliant(UUID.randomUUID().toString(), 0L)) {
            AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard();
            TracingDeploymentDiagnostics startupDiagnostics =
                    new AgentStartupVerifier(coherenceGuard, "1.2.3")
                            .verify(true, true, false, true);

            // when
            configServerRunner(coherenceGuard, startupDiagnostics).run(context -> {
                assertThat(context).hasNotFailed();
                ConfigServerRefreshWorker worker = context.getBean(ConfigServerRefreshWorker.class);
                CompletableFuture<Thread> executedOn = new CompletableFuture<>();
                worker.executor().execute(() -> executedOn.complete(Thread.currentThread()));
                workerRef.set(worker);
                workerThread.set(executedOn.get(BOUND_SECONDS, TimeUnit.SECONDS));
            });
        }

        // then
        assertThat(workerThread.get().getName()).isEqualTo(ConfigServerRefreshWorker.THREAD_NAME);
        assertThatThrownBy(() -> workerRef.get().executor().execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
        assertWorkerThreadTerminated(workerThread.get());
    }

    private static ApplicationContextRunner configServerRunner(AgentReadinessCoherenceGuard coherenceGuard,
                                                               TracingDeploymentDiagnostics startupDiagnostics) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingControlChannelAutoConfiguration.class,
                        PlatformJmxReadAutoConfiguration.class,
                        PlatformJmxControlAutoConfiguration.class,
                        ConfigServerTracingSettingsAutoConfiguration.class))
                .withBean(AgentReadinessCoherenceGuard.class, () -> coherenceGuard)
                .withBean(TracingDeploymentDiagnostics.class, () -> startupDiagnostics)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource(
                                "configserver:platform-tracing-policy",
                                Map.of(
                                        "platform.tracing.policy.sampling.enabled", "true",
                                        "platform.tracing.policy.sampling.ratio", "1.0",
                                        "platform.tracing.policy.validation.level", "WARNING",
                                        "platform.tracing.policy.exporter.enabled", "true",
                                        "platform.tracing.policy.propagation.trace-control-headers.enabled", "true",
                                        "platform.tracing.policy.diagnostics.log-level", "INFO"))))
                .withPropertyValues("platform.tracing.control.channel=CONFIG_SERVER");
    }

    private static void awaitState(Thread thread, Thread.State expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
        while (thread.getState() != expected) {
            assertThat(thread.isAlive())
                    .as("thread %s ended before reaching %s", thread.getName(), expected)
                    .isTrue();
            assertThat(System.nanoTime())
                    .as("thread %s did not reach %s", thread.getName(), expected)
                    .isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    private static void assertWorkerThreadTerminated(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
        assertThat(thread.isAlive()).as("thread %s is still alive", thread.getName()).isFalse();
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(ConfigServerRefreshWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(ConfigServerRefreshWorker.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static final class InFlightTask {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicReference<Thread> thread = new AtomicReference<>();

        static InFlightTask submitTo(ConfigServerRefreshWorker worker) throws InterruptedException {
            InFlightTask task = new InFlightTask();
            worker.executor().execute(task::run);
            assertThat(task.started.await(BOUND_SECONDS, TimeUnit.SECONDS))
                    .as("refresh task did not start on %s", ConfigServerRefreshWorker.THREAD_NAME)
                    .isTrue();
            return task;
        }

        private void run() {
            thread.set(Thread.currentThread());
            started.countDown();
            try {
                if (release.await(BOUND_SECONDS, TimeUnit.SECONDS)) {
                    completed.set(true);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        void release() {
            release.countDown();
        }

        boolean completed() {
            return completed.get();
        }

        Thread thread() {
            return thread.get();
        }
    }
}
