package space.br1440.platform.tracing.autoconfigure.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;

class TracingActuatorControlHttpContractTest {

    private static final String BASE_PATH = "/actuator/tracingControl";
    private static final String PLATFORM_AUTOCONFIGURATION_EXCLUDES = String.join(
            ",",
            "space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.SemanticLayerAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingMetricsAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingAopAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingObservationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingActuatorAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.RequestContextSupplierAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.ServiceNameProviderAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingRefreshScopeAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.async."
                    + "TracingAsyncContextAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.propagation."
                    + "PlatformOutboundPropagationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.kafka."
                    + "PlatformKafkaAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.kafka."
                    + "PlatformKafkaOutboundAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.servlet."
                    + "ServletTracingAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.servlet."
                    + "WebMvcSuppressMicrometerTracingAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.reactive."
                    + "ReactiveTracingAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.reactive."
                    + "TracingReactorEagerInitConfiguration",
            "space.br1440.platform.tracing.autoconfigure.reactive."
                    + "WebFluxSuppressMicrometerTracingAutoConfiguration");

    @Test
    void servletManagementContextExposesEightTypedRoutes() {
        verifyTypedContract(WebApplicationType.SERVLET);
    }

    @Test
    void reactiveManagementContextExposesEightTypedRoutes() {
        verifyTypedContract(WebApplicationType.REACTIVE);
    }

    @Test
    void helmManagementContextHasNoWriteRoutes() {
        try (ConfigurableApplicationContext context = start(
                TestApplication.class,
                WebApplicationType.SERVLET,
                "HELM")) {
            client(context)
                    .post()
                    .uri(BASE_PATH + "/sampling/enabled")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("value", false))
                    .exchange()
                    .expectStatus()
                    .isNotFound();
        }
    }

    @Test
    void configServerManagementContextHasNoWriteRoutes() {
        try (ConfigurableApplicationContext context = start(
                ReadOnlyTestApplication.class,
                WebApplicationType.SERVLET,
                "CONFIG_SERVER")) {
            client(context)
                    .post()
                    .uri(BASE_PATH + "/sampling/enabled")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("value", false))
                    .exchange()
                    .expectStatus()
                    .isNotFound();
        }
    }

    private void verifyTypedContract(WebApplicationType type) {
        try (ConfigurableApplicationContext context = start(
                TestApplication.class,
                type,
                "ACTUATOR")) {
            WebTestClient client = client(context);
            assertAccepted(client, HttpMethod.POST, "/sampling/enabled", false);
            assertAccepted(client, HttpMethod.POST, "/sampling/ratio", 0.25d);
            assertAccepted(
                    client,
                    HttpMethod.PUT,
                    "/sampling/route-ratios",
                    Map.of("/orders", 0.5d));
            assertAccepted(client, HttpMethod.POST, "/validation/enabled", true);
            assertAccepted(client, HttpMethod.POST, "/validation/strict", false);
            assertAccepted(client, HttpMethod.POST, "/exporter/enabled", true);
            assertAccepted(
                    client,
                    HttpMethod.POST,
                    "/propagation/trace-control-headers",
                    true);
            assertAccepted(client, HttpMethod.POST, "/diagnostics/log-level", "WARN");

            assertBadRequest(client, "/sampling/enabled", "{\"value\":\"false\"}");
            assertBadRequest(client, "/sampling/ratio", "{\"value\":\"0.25\"}");
            assertBadRequest(client, "/sampling/enabled", "{\"value\":null}");
            assertBadRequest(
                    client,
                    "/sampling/enabled",
                    "{\"value\":false,\"unexpected\":true}");
            assertOversizedRouteMapRejected(client);

            client.post()
                    .uri(BASE_PATH + "/setProperty")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("value", false))
                    .exchange()
                    .expectStatus()
                    .isNotFound();
        }
    }

    private static void assertAccepted(
            WebTestClient client,
            HttpMethod method,
            String path,
            Object value) {
        client.method(method)
                .uri(BASE_PATH + path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("value", value))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.outcome")
                .isEqualTo("APPLIED");
    }

    private static void assertBadRequest(
            WebTestClient client,
            String path,
            String body) {
        client.post()
                .uri(BASE_PATH + path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    private static void assertOversizedRouteMapRejected(WebTestClient client) {
        Map<String, Double> routes = new LinkedHashMap<>();
        for (int index = 0;
                index <= CompleteTracingPolicySnapshot.MAX_ROUTE_ENTRIES;
                index++) {
            routes.put("/route-" + index, 0.1d);
        }
        client.put()
                .uri(BASE_PATH + "/sampling/route-ratios")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("value", routes))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    private static ConfigurableApplicationContext start(
            Class<?> applicationClass,
            WebApplicationType type,
            String authority) {
        return new SpringApplicationBuilder(applicationClass)
                .web(type)
                .properties(
                        "server.port=0",
                        "management.server.port=0",
                        "management.endpoints.web.base-path=/actuator",
                        "platform.tracing.enabled=false",
                        "platform.tracing.mode=AGENT",
                        "platform.tracing.configuration.authority=" + authority,
                        "platform.environment=LOCAL",
                        "spring.autoconfigure.exclude="
                                + PLATFORM_AUTOCONFIGURATION_EXCLUDES,
                        "spring.main.banner-mode=off",
                        "logging.level.root=ERROR")
                .run();
    }

    private static WebTestClient client(ConfigurableApplicationContext context) {
        Integer port = context.getEnvironment()
                .getProperty("local.management.port", Integer.class);
        assertThat(port).isNotNull().isPositive();
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ReadOnlyTestApplication {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ImportAutoConfiguration(TracingPolicyAdaptersAutoConfiguration.class)
    static class TestApplication {

        @Bean
        AcceptedTracingPolicyReader acceptedTracingPolicyReader() {
            return TracingActuatorControlHttpContractTest::accepted;
        }

        @Bean
        TracingPolicySnapshotClient tracingPolicySnapshotClient() {
            return (expectedState, snapshot, source, trigger, sourceRevision) ->
                    new TracingPolicyDeliveryResult(
                            TracingPolicyDeliveryOutcome.APPLIED,
                            UUID.randomUUID().toString(),
                            "NONE",
                            expectedState.acceptedSequence() + 1L);
        }
    }

    private static AcceptedTracingPolicyView accepted() {
        return new AcceptedTracingPolicyView(
                UUID.randomUUID().toString(),
                7L,
                "a".repeat(64),
                new CompleteTracingPolicySnapshot(
                        true,
                        0.1d,
                        Map.of(),
                        true,
                        false,
                        true,
                        true,
                        OtelAgentLogLevel.INFO),
                "APPLIED",
                UUID.randomUUID().toString(),
                "NONE",
                false,
                false);
    }
}
