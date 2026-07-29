package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import space.br1440.platform.tracing.autoconfigure.reactive.TracingControlWebFluxManagementContextConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPrivateControlPlane;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4-WF.5 — proves WebFlux management child-context registration, custom management
 * base-path, separate management port, and main-port absence for the reactive write surface.
 */
class TracingControlWebFluxManagementChildContextIntegrationTest {

    private static final String[] EXCLUDED_UNRELATED_AUTOCONFIGURATIONS = {
            "space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.SemanticLayerAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingMetricsAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingAopAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingObservationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingActuatorAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.RequestContextSupplierAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.ServiceNameProviderAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.async.TracingAsyncContextAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.kafka.PlatformKafkaAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.kafka.PlatformKafkaOutboundAutoConfiguration",
            // Reactive app filters require TraceOperations from TracingCore; keep this slice focused
            // on management-control registration + private-JMX readiness only.
            "space.br1440.platform.tracing.autoconfigure.reactive.ReactiveTracingAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.reactive.TracingReactorEagerInitConfiguration",
            "space.br1440.platform.tracing.autoconfigure.reactive.WebFluxSuppressMicrometerTracingAutoConfiguration",
    };

    @Test
    void actuatorLocalWithCompliantAgent_createsWebFluxManagementRouteAndReachesRunningState()
            throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.compliant("runtime-instance-wf-A", 5L)) {
            try (ConfigurableApplicationContext context = boot(
                    "platform.tracing.configuration.authority=ACTUATOR",
                    "platform.environment=LOCAL",
                    "management.server.port=0",
                    "management.endpoints.web.base-path=/management",
                    "server.port=0")) {

                ApplicationContext managementContext = findManagementContext(context);
                assertThat(managementContext.containsBean("tracingControlWebFluxRoutes"))
                        .as("WebFlux router bean must exist in the management child context")
                        .isTrue();
                assertThat(managementContext.getBean(TracingControlWebFluxManagementContextConfiguration.class))
                        .isNotNull();
                assertThat(managementContext.getBeansOfType(RouterFunction.class)).isNotEmpty();
                // Note: this module's test classpath also pulls spring-boot-starter-web, so
                // Servlet TracingControl* may co-exist here via @ConditionalOnClass(WebMvcConfigurer).
                // Production reactive starter excludes spring-webmvc (see WebFluxNoServletMainDepArchTest /
                // pr0StarterDependencySmoke); physical Servlet absence on pure reactive classpaths is
                // covered by TracingControlManagementContextAbsenceTest.actuatorLocalWithoutWebMvc.

                TracingActuatorAutoConfiguration.ActuatorAgentReadinessProbe probe =
                        context.getBean(TracingActuatorAutoConfiguration.ActuatorAgentReadinessProbe.class);
                assertThat(probe.isRunning()).isTrue();

                int managementPort = managementPort(context);
                int appPort = applicationPort(context);
                assertThat(managementPort).isNotEqualTo(appPort);

                WebClient client = WebClient.builder().build();

                Map<?, ?> onManagementPort = client.post()
                        .uri("http://localhost:" + managementPort
                                + "/management/tracingControl/sampling/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("enabled", true))
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();
                assertThat(onManagementPort).isNotNull();
                assertThat(onManagementPort.get("leaf")).isEqualTo("sampling.enabled");

                assertThatThrownBy(() -> client.post()
                        .uri("http://localhost:" + appPort
                                + "/management/tracingControl/sampling/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("enabled", true))
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block())
                        .as("tracingControl must be physically absent from the application port")
                        .hasMessageContaining("404");
            }
        }
    }

    private static ConfigurableApplicationContext boot(String... properties) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(EmptyApp.class)
                .web(WebApplicationType.REACTIVE)
                .properties(properties)
                .properties("spring.autoconfigure.exclude="
                        + String.join(",", EXCLUDED_UNRELATED_AUTOCONFIGURATIONS));
        return builder.run();
    }

    private static ApplicationContext findManagementContext(ApplicationContext appContext)
            throws Exception {
        for (String name : appContext.getBeanNamesForType(
                org.springframework.context.SmartLifecycle.class)) {
            Object bean = appContext.getBean(name);
            if (bean.getClass().getName().endsWith("ChildManagementContextInitializer")) {
                java.lang.reflect.Field field = bean.getClass().getDeclaredField("managementContext");
                field.setAccessible(true);
                ConfigurableApplicationContext managementContext =
                        (ConfigurableApplicationContext) field.get(bean);
                if (managementContext != null) {
                    return managementContext;
                }
            }
        }
        throw new IllegalStateException("No started management child context found");
    }

    private static int managementPort(ApplicationContext appContext) {
        return Integer.parseInt(appContext.getEnvironment().getProperty("local.management.port"));
    }

    private static int applicationPort(ApplicationContext appContext) {
        return Integer.parseInt(appContext.getEnvironment().getProperty("local.server.port"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class EmptyApp {
    }
}
