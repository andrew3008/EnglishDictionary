package space.br1440.platform.tracing.autoconfigure.control.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentStartupVerifier;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;

class TracingControlActuatorSecurityIntegrationTest {

    private static final String EXTENSION_VERSION = "1.2.3";
    private static final String[] EXCLUDED_UNRELATED_AUTOCONFIGURATIONS = {
            "space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.attributes.SpanAttributeValidationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.exception.ExceptionRecordingAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.span.SpanEnrichmentAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.metrics.TracingMetricsAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.aop.TracingAopAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.observation.TracingObservationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup.TracingStartupContractHealthAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.errorhandling.RequestContextSupplierAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.identity.ServiceNameProviderAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaOutboundAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.servlet.ServletTracingAutoConfiguration"
    };

    @Test
    void applicationSecurityChainRejectsUnauthenticatedControlRequest() throws Exception {
        // given
        try (FakeAgentPlatformJmxPlane ignored = FakeAgentPlatformJmxPlane.compliant(
                "13b74f6d-189f-4939-b17d-779f46d65be8",
                5L
        );
             ConfigurableApplicationContext context = boot()) {
            String url = "http://localhost:" + applicationPort(context)
                    + "/management/tracingControl/sampling/ratio";
            RestTemplate anonymous = new RestTemplateBuilder().build();
            RestTemplate authenticated = new RestTemplateBuilder()
                    .basicAuthentication("operator", "secret")
                    .build();

            // when
            Throwable anonymousFailure = catchThrowable(() -> anonymous.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("ratio", 0.25)),
                    new ParameterizedTypeReference<Map<String, Object>>() {}));
            ResponseEntity<Map<String, Object>> response = authenticated.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("ratio", 0.25)),
                    new ParameterizedTypeReference<>() {});

            // then
            assertThat(anonymousFailure).isInstanceOf(HttpClientErrorException.Unauthorized.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).containsEntry("leaf", "sampling.ratio");
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
        }
    }

    private static ConfigurableApplicationContext boot() {
        return new SpringApplicationBuilder(SecuredApp.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "platform.tracing.control.channel=ACTUATOR",
                        "management.endpoints.web.base-path=/management",
                        "management.endpoints.web.exposure.include=tracingControl",
                        "server.port=0",
                        "spring.cloud.config.enabled=false",
                        "spring.autoconfigure.exclude=" + String.join(",", EXCLUDED_UNRELATED_AUTOCONFIGURATIONS))
                .run();
    }

    private static int applicationPort(ConfigurableApplicationContext context) {
        return context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class SecuredApp {

        @Bean
        SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .authorizeHttpRequests(authorize -> authorize
                            .requestMatchers("/management/tracingControl/**").authenticated()
                            .anyRequest().permitAll())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(csrf -> csrf.ignoringRequestMatchers("/management/tracingControl/**"))
                    .build();
        }

        @Bean
        UserDetailsService applicationUsers() {
            return new InMemoryUserDetailsManager(User.withUsername("operator")
                    .password("{noop}secret")
                    .roles("TRACING_OPERATOR")
                    .build());
        }

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
