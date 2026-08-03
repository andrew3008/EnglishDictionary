package space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestTemplate;

import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тонкий HTTP wire-contract тест: {@link space.br1440.platform.tracing.autoconfigure.diagnostics
 * .health.startup.TracingStartupContractHealthComponentModelTest} через {@code HealthEndpoint} не
 * покрывает HTTP-сериализацию, show-details policy, фактический JSON path и HTTP status mapping —
 * этот тест проверяет ровно их через реальный {@link SpringApplicationBuilder} boot на случайном
 * порту (тот же паттерн, что {@code TracingControlManagementChildContextIntegrationTest}), исключая
 * из auto-configuration только несвязанные с health модули (Kafka, AOP, propagation), а не весь
 * production-стек.
 */
class TracingStartupContractHealthHttpContractTest {

    private static final String[] EXCLUDED_UNRELATED_AUTOCONFIGURATIONS = {
            "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.metrics.export.otlp.OtlpMetricsExportAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.semantic.SemanticLayerAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.metrics.TracingMetricsAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.aop.TracingAopAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.observation.TracingObservationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.errorhandling.RequestContextSupplierAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.identity.ServiceNameProviderAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.async.TracingAsyncContextAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaOutboundAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.servlet.ServletTracingAutoConfiguration",
    };

    @Test
    void enabledContractExposesTracingStartupContractKeyOverHttpAndOmitsLegacyTracingKey() throws Exception {
        try (ControlledAgentTestRuntime ignored = ControlledAgentTestRuntime.start()) {
            try (ConfigurableApplicationContext context = boot(
                    "platform.tracing.enabled=true",
                    "platform.tracing.configuration.authority=HELM",
                    "platform.environment=LOCAL",
                    "server.port=0",
                    "management.endpoints.web.exposure.include=health",
                    "management.endpoint.health.show-details=always")) {

                int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
                RestTemplate rest = new RestTemplateBuilder().build();

                String body = rest.getForObject("http://localhost:" + port + "/actuator/health", String.class);

                assertThat(body).contains("\"tracingStartupContract\"");
                assertThat(body).doesNotContain("\"tracing\":");
            }
        }
    }

    private static ConfigurableApplicationContext boot(String... properties) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(EmptyApp.class)
                .web(WebApplicationType.SERVLET)
                .properties(properties)
                .properties("spring.autoconfigure.exclude="
                        + String.join(",", EXCLUDED_UNRELATED_AUTOCONFIGURATIONS));
        return builder.run();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class EmptyApp {
    }
}
