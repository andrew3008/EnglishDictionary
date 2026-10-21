package space.br1440.platform.tracing.autoconfigure.control.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentStartupVerifier;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;

class TracingControlActuatorServletIntegrationTest {

    private static final String EXTENSION_VERSION = "1.2.3";
    private static final String[] EXCLUDED_UNRELATED_AUTOCONFIGURATIONS = {
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
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
            "space.br1440.platform.tracing.autoconfigure.servlet.ServletTracingAutoConfiguration"
    };

    @Test
    void actuatorExposesAllWriteOperationsOnlyOnManagementPort() throws Exception {
        try (FakeAgentPlatformJmxPlane ignored = FakeAgentPlatformJmxPlane.compliant(
                "f3dd5524-d013-44d9-af59-06cf962be3d2",
                5L
        );
             ConfigurableApplicationContext context = boot()) {
            assertThat(context.getBeansOfType(TracingControlEndpoint.class)).hasSize(1);
            assertThat(context.getBean(ActuatorAgentReadinessProbe.class)
                    .isRunning()).isTrue();

            int managementPort = managementPort(context);
            int applicationPort = applicationPort(context);
            assertThat(managementPort).isNotEqualTo(applicationPort);

            RestTemplate rest = new RestTemplateBuilder().build();
            RouteCase[] routes = {
                    new RouteCase("sampling/enabled", Map.of("enabled", false), "sampling.enabled"),
                    new RouteCase("sampling/ratio", Map.of("ratio", 0.5), "sampling.ratio"),
                    new RouteCase("sampling/route-ratios", Map.of("routeRatios", "{\"api\":0.8}"),
                            "sampling.route-ratios"),
                    new RouteCase("validation/level", Map.of("level", "ERROR"), "validation.level"),
                    new RouteCase("exporter/enabled", Map.of("enabled", false), "exporter.enabled"),
                    new RouteCase("propagation/trace-control-headers-enabled", Map.of("enabled", false),
                            "propagation.trace-control-headers.enabled"),
                    new RouteCase("diagnostics/log-level", Map.of("level", "DEBUG"), "diagnostics.log-level")
            };

            for (RouteCase route : routes) {
                ResponseEntity<Map<String, Object>> response = rest.exchange(
                        url(managementPort, route.path()),
                        HttpMethod.POST,
                        new HttpEntity<>(route.body()),
                        new ParameterizedTypeReference<>() {});
                assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
                assertThat(response.getBody()).containsEntry("leaf", route.expectedLeaf());
            }

            assertThatThrownBy(() -> rest.exchange(
                    url(applicationPort, "sampling/enabled"),
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("enabled", true)),
                    new ParameterizedTypeReference<Map<String, Object>>() {}))
                    .isInstanceOf(HttpClientErrorException.NotFound.class);
        }
    }

    @Test
    void incompatibleAgentBlocksActuatorControlStartup() throws Exception {
        try (FakeAgentPlatformJmxPlane ignored = FakeAgentPlatformJmxPlane.missingAcceptedPolicySnapshot(
                "a31ef6c8-1620-4746-b21b-c2345b74f5c4"
        )) {
            assertThatThrownBy(TracingControlActuatorServletIntegrationTest::boot)
                    .isInstanceOf(Exception.class);
        }
    }

    private static ConfigurableApplicationContext boot() {
        return new SpringApplicationBuilder(EmptyApp.class)
                .web(WebApplicationType.SERVLET)
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

    private static String url(int port, String path) {
        return "http://localhost:" + port + "/management/tracingControl/" + path;
    }

    private static int managementPort(ApplicationContext context) {
        return Integer.parseInt(context.getEnvironment().getRequiredProperty("local.management.port"));
    }

    private static int applicationPort(ApplicationContext context) {
        return Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    private record RouteCase(String path, Map<String, ?> body, String expectedLeaf) {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class EmptyApp {

        @Bean
        AgentReadinessCoherenceGuard agentReadinessCoherenceGuard() {
            return new AgentReadinessCoherenceGuard();
        }

        @Bean
        TracingDeploymentDiagnostics platformTracingDeploymentDiagnostics(
                AgentReadinessCoherenceGuard coherenceGuard) {
            return new AgentStartupVerifier(coherenceGuard, () -> EXTENSION_VERSION)
                    .verify(true, true, false, true);
        }
    }
}
