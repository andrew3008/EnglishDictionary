package space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestTemplate;

import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;

class TracingStartupContractHealthHttpContractTest {

    private static final String AGENT_STARTUP_VERIFIER_BEAN_NAME = "agentStartupVerifier";

    private static final String[] EXCLUDED_UNRELATED_AUTOCONFIGURATIONS = {
            "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.metrics.export.otlp.OtlpMetricsExportAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.attributes.SpanAttributeValidationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.exception.ExceptionRecordingAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.span.SpanEnrichmentAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.metrics.TracingMetricsAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.aop.TracingAopAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.observation.TracingObservationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.errorhandling.RequestContextSupplierAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.identity.ServiceNameProviderAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaOutboundAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.servlet.ServletTracingAutoConfiguration"
    };

    @Test
    void enabledContractExposesTracingStartupContractKeyOverHttpAndOmitsLegacyTracingKey() {
        try (ControlledAgentTestRuntime ignored = ControlledAgentTestRuntime.start()) {
            try (ConfigurableApplicationContext context = boot(
                    "platform.tracing.enabled=true",
                    "platform.tracing.control.channel=STATIC",
                    "server.port=0",
                    "management.endpoints.web.exposure.include=health",
                    "management.endpoint.health.show-details=always")) {
                RestTemplate rest = new RestTemplateBuilder().build();
                int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
                String body = rest.getForObject("http://localhost:" + port + "/actuator/health", String.class);

                assertThat(body).contains("\"tracingStartupContract\"");
                assertThat(body).doesNotContain("\"tracing\":");
            }
        }
    }

    private static ConfigurableApplicationContext boot(String... properties) {
        List<String> allProperties = new ArrayList<>(List.of(properties));
        String excluded = String.join(",", EXCLUDED_UNRELATED_AUTOCONFIGURATIONS);
        allProperties.add("spring.autoconfigure.exclude=" + excluded);

        return new SpringApplicationBuilder(EmptyApp.class)
                .web(WebApplicationType.SERVLET)
                .properties(allProperties.toArray(String[]::new))
                .initializers(TracingStartupContractHealthHttpContractTest::registerAgentStartupVerifier)
                .run();
    }

    private static void registerAgentStartupVerifier(ConfigurableApplicationContext context) {
        context.getBeanFactory().registerSingleton(
                AGENT_STARTUP_VERIFIER_BEAN_NAME,
                ControlledAgentTestRuntime.verifier()
        );
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class EmptyApp {
    }
}
