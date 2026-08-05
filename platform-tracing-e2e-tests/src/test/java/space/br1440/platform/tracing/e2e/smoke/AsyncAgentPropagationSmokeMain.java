package space.br1440.platform.tracing.e2e.smoke;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/**
 * Минимальное packaged Spring MVC приложение для проверки {@code @Async} под Controlled Agent.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "space.br1440.platform.logging.configuration.LoggingAutoConfiguration",
        "space.br1440.platform.logging.configuration.GrpcLoggingConfiguration",
        "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration"
})
@Import({
        AsyncAgentPropagationSmokeMain.AsyncProbeConfiguration.class,
        AsyncAgentPropagationSmokeMain.AsyncProbeController.class,
        AsyncAgentPropagationSmokeMain.AsyncProbeService.class
})
public class AsyncAgentPropagationSmokeMain {

    private static final String AUTO_EXECUTOR_BEAN_NAME = "applicationTaskExecutor";
    private static final String CUSTOM_EXECUTOR_BEAN_NAME = "customAsyncExecutor";

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        long flushDelayMs = Long.parseLong(args[1]);
        SpringApplication application = new SpringApplication(AsyncAgentPropagationSmokeMain.class);
        application.setRegisterShutdownHook(false);

        try (ConfigurableApplicationContext context = application.run(
                "--server.port=" + port,
                "--spring.main.banner-mode=off",
                "--spring.threads.virtual.enabled=false",
                "--spring.task.execution.pool.core-size=1",
                "--spring.task.execution.pool.max-size=1",
                "--spring.task.execution.pool.queue-capacity=8",
                "--spring.task.execution.thread-name-prefix=async-agent-auto-",
                "--platform.tracing.suppression.suppress-micrometer-tracing=true",
                "--logging.level.root=WARN")) {
            String mode = context.getEnvironment().getRequiredProperty("async.probe.executor-mode");
            TracingDeploymentDiagnostics diagnostics = context.getBean(TracingDeploymentDiagnostics.class);
            print(mode, "agentDetected", diagnostics.agentDetected());
            print(mode, "runtimeState", diagnostics.runtimeState());
            print(mode, "openTelemetryBeans", context.getBeansOfType(OpenTelemetry.class).size());

            if ("CUSTOM".equals(mode)) {
                ProbeThreadPoolTaskExecutor executor = context.getBean(
                        CUSTOM_EXECUTOR_BEAN_NAME, ProbeThreadPoolTaskExecutor.class);
                print(mode, "customTaskDecoratorConfigured", executor.isTaskDecoratorConfigured());
            }

            System.out.println(AgentHttpSpringSmokeProcessRunner.READY_MARKER);
            System.out.flush();

            if (!context.getBean(CountDownLatch.class).await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Запрос /async-agent не получен за 60 секунд");
            }
            Thread.sleep(flushDelayMs);
        }
    }

    private static void print(String mode, String key, Object value) {
        System.out.println("ASYNC_AGENT:" + mode + ":" + key + "=" + value);
    }

    private enum ExecutorMode {
        AUTO(AUTO_EXECUTOR_BEAN_NAME),
        CUSTOM(CUSTOM_EXECUTOR_BEAN_NAME);

        private final String executorBeanName;

        ExecutorMode(String executorBeanName) {
            this.executorBeanName = executorBeanName;
        }
    }

    record ProbeSnapshot(
            boolean traceValid,
            String traceId,
            String requestId,
            String correlationId,
            String mdcRequestId,
            String mdcCorrelationId,
            String thread) {

        static ProbeSnapshot capture(TraceOperations traceOperations) {
            SpanContext spanContext = Span.current().getSpanContext();
            return new ProbeSnapshot(
                    spanContext.isValid(),
                    spanContext.isValid() ? spanContext.getTraceId() : "empty",
                    traceOperations.traceContext().requestId().orElse("empty"),
                    traceOperations.traceContext().correlationId().orElse("empty"),
                    Optional.ofNullable(MDC.get(TracingMdcKeys.REQUEST_ID)).orElse("empty"),
                    Optional.ofNullable(MDC.get(TracingMdcKeys.CORRELATION_ID)).orElse("empty"),
                    Thread.currentThread().getName());
        }
    }

    record CleanProbeResult(ProbeSnapshot source, ProbeSnapshot worker) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class AsyncProbeConfiguration {

        @Bean
        CountDownLatch servedLatch() {
            return new CountDownLatch(1);
        }

        @Bean(name = CUSTOM_EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
        @ConditionalOnProperty(name = "async.probe.executor-mode", havingValue = "CUSTOM")
        ProbeThreadPoolTaskExecutor customAsyncExecutor() {
            ProbeThreadPoolTaskExecutor executor = new ProbeThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(8);
            executor.setThreadNamePrefix("async-agent-custom-");
            return executor;
        }

        @Bean(destroyMethod = "close")
        ContextFreeTaskSubmitter contextFreeTaskSubmitter(TraceOperations traceOperations) {
            return new ContextFreeTaskSubmitter(traceOperations);
        }
    }

    static class ProbeThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {

        private boolean taskDecoratorConfigured;

        @Override
        public void setTaskDecorator(TaskDecorator taskDecorator) {
            taskDecoratorConfigured = true;
            super.setTaskDecorator(taskDecorator);
        }

        boolean isTaskDecoratorConfigured() {
            return taskDecoratorConfigured;
        }
    }

    static class AsyncProbeService {

        private final TraceOperations traceOperations;

        AsyncProbeService(TraceOperations traceOperations) {
            this.traceOperations = traceOperations;
        }

        @Async
        public CompletableFuture<ProbeSnapshot> observeAutoExecutor() {
            return CompletableFuture.completedFuture(ProbeSnapshot.capture(traceOperations));
        }

        @Async(CUSTOM_EXECUTOR_BEAN_NAME)
        public CompletableFuture<ProbeSnapshot> observeCustomExecutor() {
            return CompletableFuture.completedFuture(ProbeSnapshot.capture(traceOperations));
        }
    }

    @RestController
    static class AsyncProbeController {

        private final ApplicationContext applicationContext;
        private final AsyncProbeService asyncProbeService;
        private final ContextFreeTaskSubmitter cleanTaskSubmitter;
        private final TraceOperations traceOperations;
        private final CountDownLatch servedLatch;
        private final ExecutorMode mode;
        private final String correlationId;

        AsyncProbeController(ApplicationContext applicationContext,
                             AsyncProbeService asyncProbeService,
                             ContextFreeTaskSubmitter cleanTaskSubmitter,
                             TraceOperations traceOperations,
                             CountDownLatch servedLatch,
                             @Value("${async.probe.executor-mode}") ExecutorMode mode,
                             @Value("${async.probe.correlation-id}") String correlationId) {
            this.applicationContext = applicationContext;
            this.asyncProbeService = asyncProbeService;
            this.cleanTaskSubmitter = cleanTaskSubmitter;
            this.traceOperations = traceOperations;
            this.servedLatch = servedLatch;
            this.mode = mode;
            this.correlationId = correlationId;
        }

        @GetMapping("/async-agent")
        String probe() throws Exception {
            try {
                ProbeSnapshot caller;
                ProbeSnapshot async;
                try (CorrelationScope ignored = traceOperations.openCorrelationScope(correlationId)) {
                    caller = ProbeSnapshot.capture(traceOperations);
                    async = asyncObservation().get(10, TimeUnit.SECONDS);
                }

                Executor executor = applicationContext.getBean(mode.executorBeanName, Executor.class);
                CleanProbeResult clean = cleanTaskSubmitter
                        .submit(executor, () -> ProbeSnapshot.capture(traceOperations))
                        .get(10, TimeUnit.SECONDS);

                printSnapshot("caller", caller);
                printSnapshot("async", async);
                print(mode.name(), "clean.sourceTraceValid", clean.source().traceValid());
                printSnapshot("clean", clean.worker());
                System.out.flush();
                return "ok";
            } finally {
                servedLatch.countDown();
            }
        }

        private CompletableFuture<ProbeSnapshot> asyncObservation() {
            return switch (mode) {
                case AUTO -> asyncProbeService.observeAutoExecutor();
                case CUSTOM -> asyncProbeService.observeCustomExecutor();
            };
        }

        private void printSnapshot(String prefix, ProbeSnapshot snapshot) {
            print(mode.name(), prefix + ".traceValid", snapshot.traceValid());
            print(mode.name(), prefix + ".traceId", snapshot.traceId());
            print(mode.name(), prefix + ".requestId", snapshot.requestId());
            print(mode.name(), prefix + ".correlationId", snapshot.correlationId());
            print(mode.name(), prefix + ".mdcRequestId", snapshot.mdcRequestId());
            print(mode.name(), prefix + ".mdcCorrelationId", snapshot.mdcCorrelationId());
            print(mode.name(), prefix + ".thread", snapshot.thread());
        }
    }

    static final class ContextFreeTaskSubmitter implements AutoCloseable {

        private final TraceOperations traceOperations;
        private final BlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();
        private final Thread coordinator;

        ContextFreeTaskSubmitter(TraceOperations traceOperations) {
            this.traceOperations = traceOperations;
            this.coordinator = new Thread(this::runLoop, "async-agent-clean-submitter");
            this.coordinator.setDaemon(true);
            this.coordinator.start();
        }

        CompletableFuture<CleanProbeResult> submit(Executor executor, Supplier<ProbeSnapshot> probe) {
            CompletableFuture<CleanProbeResult> result = new CompletableFuture<>();
            commands.add(() -> {
                ProbeSnapshot source = ProbeSnapshot.capture(traceOperations);
                executor.execute(() -> {
                    try {
                        result.complete(new CleanProbeResult(source, probe.get()));
                    } catch (RuntimeException failure) {
                        result.completeExceptionally(failure);
                    }
                });
            });
            return result;
        }

        private void runLoop() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    commands.take().run();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() throws InterruptedException {
            coordinator.interrupt();
            coordinator.join(5_000L);
        }
    }
}
