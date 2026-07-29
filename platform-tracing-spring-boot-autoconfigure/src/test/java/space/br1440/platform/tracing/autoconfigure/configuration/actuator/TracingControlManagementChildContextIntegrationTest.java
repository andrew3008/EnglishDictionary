package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPrivateControlPlane;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves — with a real two-port {@link SpringApplication} boot, a real Agent-side
 * private control plane simulated on the JVM-wide {@code MBeanServerFactory}
 * registry, and real HTTP requests — that the management child context described
 * in {@code TracingControlManagementContextConfiguration} actually creates (or
 * physically omits) the mutating tracing-control API, and that
 * {@code ActuatorAgentReadinessProbe} actually gates startup on Agent
 * reachability. {@code MockMvc}/{@code standaloneSetup}-based tests
 * ({@code TracingControlMvcControllerHttpTest}) prove route-level dispatch only;
 * they do not prove child-context creation, base-path composition on a real
 * management server, or startup-blocking behaviour — that is this test's purpose.
 * <p>
 * Real, exact production property names used (not paraphrased):
 * {@code management.server.port}, {@code management.endpoints.web.base-path},
 * {@code platform.tracing.configuration.authority}, {@code platform.environment}.
 */
class TracingControlManagementChildContextIntegrationTest {

    private static final String[] EXCLUDED_UNRELATED_AUTOCONFIGURATIONS = {
            "space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.SemanticLayerAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingMetricsAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingAopAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingObservationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingActuatorAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.RequestContextSupplierAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.ServiceNameProviderAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingRefreshScopeAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.async.TracingAsyncContextAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.kafka.PlatformKafkaAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.kafka.PlatformKafkaOutboundAutoConfiguration",
    };

    @Test
    void actuatorLocalWithCompliantAgent_createsManagementRouteAndReachesRunningState() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.compliant("runtime-instance-A", 5L)) {
            try (ConfigurableApplicationContext context = boot(
                    "platform.tracing.configuration.authority=ACTUATOR",
                    "platform.environment=LOCAL",
                    "management.server.port=0",
                    "management.endpoints.web.base-path=/management",
                    "server.port=0")) {

                ApplicationContext managementContext = findManagementContext(context);
                assertThat(managementContext.containsBean("tracingControlMvcController"))
                        .as("controller bean must exist in the management child context, not merely be routable")
                        .isTrue();
                assertThat(managementContext.getBean(TracingControlMvcController.class)).isNotNull();
                assertThat(managementContext.containsBean("tracingControlPathPrefixConfigurer"))
                        .as("configuration bean must exist in the management child context")
                        .isTrue();

                // ActuatorAgentReadinessProbe and SpringActuatorTracingPolicyAdapter are registered
                // in the root context (TracingActuatorAutoConfiguration.ActuatorAdapterConfiguration
                // is a plain nested @Configuration, not @ManagementContextConfiguration); the
                // management child context's TracingControlMvcController resolves
                // SpringActuatorTracingPolicyAdapter from the shared parent context.
                TracingActuatorAutoConfiguration.ActuatorAgentReadinessProbe probe =
                        context.getBean(TracingActuatorAutoConfiguration.ActuatorAgentReadinessProbe.class);
                assertThat(probe.isRunning())
                        .as("readiness probe must reach RUNNING after a successful readAcceptedComplete")
                        .isTrue();

                int managementPort = managementPort(context);
                int appPort = applicationPort(context);
                assertThat(managementPort).isNotEqualTo(appPort);

                RestTemplate rest = new RestTemplateBuilder().build();

                ResponseEntity<Map> onManagementPort = rest.postForEntity(
                        "http://localhost:" + managementPort + "/management/tracingControl/sampling/enabled",
                        Map.of("enabled", true), Map.class);
                assertThat(onManagementPort.getStatusCode().is2xxSuccessful())
                        .as("custom management base-path route must be reachable on the management port: "
                                + onManagementPort.getStatusCode())
                        .isTrue();

                assertThatThrownBy(() -> rest.postForEntity(
                        "http://localhost:" + appPort + "/management/tracingControl/sampling/enabled",
                        Map.of("enabled", true), Map.class))
                        .as("tracingControl must be physically absent from the application port")
                        .isInstanceOf(HttpClientErrorException.NotFound.class);
            }
        }
    }

    @Test
    void actuatorLocalWithAgentMissingReadAcceptedComplete_blocksStartup() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.missingReadAcceptedComplete("runtime-instance-B")) {
            assertThatThrownBy(() -> boot(
                    "platform.tracing.configuration.authority=ACTUATOR",
                    "platform.environment=LOCAL",
                    "management.server.port=0",
                    "management.endpoints.web.base-path=/management",
                    "server.port=0"))
                    .as("an Agent that never implemented PROPOSED_PRIV_R07 must block startup, "
                            + "not silently start in a degraded state")
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void actuatorLocalWithMalformedReadAcceptedCompleteResponse_blocksStartup() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.malformedReadAcceptedComplete("runtime-instance-C")) {
            assertThatThrownBy(() -> boot(
                    "platform.tracing.configuration.authority=ACTUATOR",
                    "platform.environment=LOCAL",
                    "management.server.port=0",
                    "management.endpoints.web.base-path=/management",
                    "server.port=0"))
                    .as("a structurally malformed readAcceptedComplete response must block startup")
                    .isInstanceOf(Exception.class);
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

    /**
     * Spring Boot's {@code ChildManagementContextInitializer} (a package-private
     * {@link org.springframework.context.SmartLifecycle} registered in the root context by
     * {@code EnableChildManagementContextConfiguration}) creates and holds the management
     * child {@link ConfigurableApplicationContext} in a private field with no public
     * accessor or well-known bean name. Reflection is the only way to reach the actual
     * child context object from outside Boot's own internals — a narrow, documented
     * exception to preferring non-reflective test code, justified because the alternative
     * (asserting solely on HTTP reachability) would not prove bean-level child-context
     * membership as the task requires.
     */
    private static ApplicationContext findManagementContext(ApplicationContext appContext) throws Exception {
        for (String name : appContext.getBeanNamesForType(org.springframework.context.SmartLifecycle.class)) {
            Object bean = appContext.getBean(name);
            if (bean.getClass().getName().endsWith("ChildManagementContextInitializer")) {
                java.lang.reflect.Field field = bean.getClass().getDeclaredField("managementContext");
                field.setAccessible(true);
                ConfigurableApplicationContext managementContext = (ConfigurableApplicationContext) field.get(bean);
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
