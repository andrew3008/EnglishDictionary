package space.br1440.platform.tracing.autoconfigure.control.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentStartupVerifier;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;

class TracingControlActuatorWebFluxIntegrationTest {

    private static final String EXTENSION_VERSION = "1.2.3";
    private static final String[] EXCLUDED_UNRELATED_AUTOCONFIGURATIONS = {
            "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.security.reactive.ReactiveManagementWebSecurityAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.attributes.SpanAttributeValidationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.exception.ExceptionRecordingAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.span.SpanEnrichmentAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.metrics.TracingMetricsAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.aop.TracingAopAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.observation.TracingObservationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup. TracingStartupContractHealthAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.errorhandling.RequestContextSupplierAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.identity.ServiceNameProviderAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaOutboundAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.reactive.ReactiveTracingAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.reactive.TracingReactorEagerInitConfiguration",
            "space.br1440.platform.tracing.autoconfigure.reactive.WebFluxHttpObservationSuppressionAutoConfiguration"
    };

    @Test
    void actuatorWriteOperationIsStackNeutral() throws Exception {
        try (FakeAgentPlatformJmxPlane ignored = FakeAgentPlatformJmxPlane.compliant(
                "3f68c6e4-50f3-497a-9416-01b093ce3dfd",
                5L
        );
             ConfigurableApplicationContext context = boot()) {
            assertThat(context.getBeansOfType(TracingControlEndpoint.class)).hasSize(1);

            int managementPort = managementPort(context);
            int applicationPort = applicationPort(context);
            WebClient client = WebClient.builder().build();

            Map<?, ?> response = client.post()
                    .uri(url(managementPort))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("ratio", 0.25))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            assertThat(response).isNotNull();
            assertThat(response.get("outcome")).isEqualTo("APPLIED");
            assertThat(response.get("leaf")).isEqualTo("sampling.ratio");
            assertThat(response.get("value")).isEqualTo(0.25);

            assertThatThrownBy(() -> client.post()
                    .uri(url(applicationPort))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("ratio", 0.25))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block())
                    .hasMessageContaining("404");
        }
    }

    private static ConfigurableApplicationContext boot() {
        return new SpringApplicationBuilder(EmptyApp.class)
                .web(WebApplicationType.REACTIVE)
                .properties(
                        "platform.tracing.control.channel=ACTUATOR",
                        "management.server.port=0",
                        "management.endpoints.web.base-path=/management",
                        "management.endpoints.web.exposure.include=tracingControl",
                        "server.port=0",
                        "spring.cloud.config.enabled=false",
                        "spring.autoconfigure.exclude=" + String.join(",", EXCLUDED_UNRELATED_AUTOCONFIGURATIONS))
                .run();
    }

    private static String url(int port) {
        return "http://localhost:" + port + "/management/tracingControl/sampling/ratio";
    }

    private static int managementPort(ApplicationContext context) {
        return context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
    }

    private static int applicationPort(ApplicationContext context) {
        return context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class EmptyApp {

        @Bean
        AgentReadinessCoherenceGuard agentReadinessCoherenceGuard() {
            return new AgentReadinessCoherenceGuard();
        }

        @Bean
        TracingDeploymentDiagnostics platformTracingDeploymentDiagnostics(AgentReadinessCoherenceGuard coherenceGuard) {
            return new AgentStartupVerifier(coherenceGuard, () -> EXTENSION_VERSION)
                    .verify(true, true, false, true);
        }
    }
}
