package space.br1440.platform.tracing.e2e.smoke;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;

/**
 * Packaged smoke-main для {@link OutboundBaggageAgentE2ETest} (план §6.5): минимальный Spring
 * Boot Web, единственная ответственность которого — вызвать {@code /probe}, положить baggage в
 * {@link io.opentelemetry.context.Context} и выполнить один outbound HTTP-запрос через
 * Agent-instrumented {@code java.net.http.HttpClient} к локальному test-only receiver'у.
 *
 * <p>Экспортер трейсов отключается через {@code otel.traces.exporter=none} (тест не зависит от
 * Jaeger/Collector); предмет проверки — исключительно wire-байты {@code baggage} заголовка,
 * захваченные независимым JDK {@code HttpServer}.
 *
 * <p>Platform Spring-библиотека ({@code platform-tracing-spring-boot-autoconfigure}) исключена
 * целиком и одинаково в обоих прогонах (positive/negative): её собственный readiness-контракт
 * ({@code AgentStartupVerifier}) требует AGENT_READY/DISABLED состояние независимо от значения
 * {@code platform.tracing.enabled} всякий раз, когда OTel Java Agent физически присутствует —
 * это не предмет данного теста (см. class Javadoc {@link OutboundBaggageAgentE2ETest}).
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
        "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.status.TracingStatusAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.semantic.SemanticLayerAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.metrics.TracingMetricsAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.integrations.aop.TracingAopAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.integrations.observation.TracingObservationAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup."
                + "TracingStartupContractHealthAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.integrations.errorhandling.RequestContextSupplierAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.identity.ServiceNameProviderAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaOutboundAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.servlet.ServletTracingAutoConfiguration",
        "space.br1440.platform.tracing.autoconfigure.servlet.WebMvcSuppressMicrometerTracingAutoConfiguration"
})
@Import(OutboundBaggageProbeController.class)
public class AgentSpringOutboundBaggageSmokeMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: AgentSpringOutboundBaggageSmokeMain <port> <flushDelayMs>");
        }
        int port = Integer.parseInt(args[0]);
        long flushDelayMs = Long.parseLong(args[1]);

        SpringApplication application = new SpringApplication(AgentSpringOutboundBaggageSmokeMain.class);
        application.setRegisterShutdownHook(false);

        ConfigurableApplicationContext context = application.run(
                "--server.port=" + port,
                "--spring.main.banner-mode=off",
                "--platform.tracing.suppression.suppress-micrometer-tracing=true",
                "--logging.level.root=WARN");
        try (context) {
            CountDownLatch served = context.getBean(CountDownLatch.class);
            System.out.println(AgentHttpSpringSmokeProcessRunner.READY_MARKER);
            System.out.flush();

            if (!served.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Внешний HTTP-запрос к /probe не получен за 60 с");
            }
            Thread.sleep(flushDelayMs);
        }
    }

    @Bean
    CountDownLatch servedLatch() {
        return new CountDownLatch(1);
    }
}
