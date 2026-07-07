package space.br1440.platform.tracing.e2e.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;
import space.br1440.platform.tracing.e2e.support.JaegerTestContainerSupport;
import space.br1440.platform.tracing.e2e.support.JaegerV3QueryClient;

import java.io.File;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Smoke-тест верификации Status Mapping для Java Agent.
 * <p>
 * Доказывает, что OTel Java Agent корректно выставляет StatusCode.ERROR
 * для необработанных исключений и HTTP 5xx ответов.
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "Smoke-тесты пропущены через -DskipE2e=true")
class AgentStatusMappingSmokeTest {

    private static final String SERVICE_NAME = "agent-status-mapping-smoke";
    private static final String ERROR_ROUTE = "/error";
    private static final Duration TRACE_VISIBILITY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(2);

    private static GenericContainer<?> jaeger;
    private static JaegerV3QueryClient jaegerClient;
    private static String otlpEndpoint;
    private static String testRuntimeClasspath;
    private static String otelAgentJar;

    @BeforeAll
    static void setUpStack() {
        otelAgentJar = System.getProperty("otel.javaagent.jar");
        testRuntimeClasspath = System.getProperty("smoke.test.runtime.classpath");

        assertThat(otelAgentJar).isNotBlank();
        assertThat(testRuntimeClasspath).isNotBlank();
        assertThat(new File(otelAgentJar)).exists().isFile();

        jaeger = JaegerTestContainerSupport.newJaeger();
        jaeger.start();

        otlpEndpoint = JaegerTestContainerSupport.otlpHttpEndpoint(jaeger);
        jaegerClient = new JaegerV3QueryClient(JaegerTestContainerSupport.queryBaseUrl(jaeger));
    }

    @AfterAll
    static void tearDownStack() {
        if (jaeger != null) {
            jaeger.stop();
        }
    }

    @Test
    void unhandledException_shouldMapToStatusCodeError() throws Exception {
        int httpPort = freePort();

        AgentHttpSpringSmokeProcessRunner.run(
                AgentSpringForceSamplingSmokeMain.class.getName(),
                otelAgentJar,
                testRuntimeClasspath,
                otlpEndpoint,
                SERVICE_NAME,
                httpPort,
                null, // no extensionLocation
                ERROR_ROUTE,
                null, // no custom headers
                Map.of("OTEL_TRACES_SAMPLER", "always_on", "PLATFORM_TRACING_SUPPRESSION_SUPPRESS_MICROMETER_TRACING", "true"),
                List.of("otel.bsp.schedule.delay=200"),
                PROCESS_TIMEOUT,
                3_000L);

        await().atMost(TRACE_VISIBILITY_TIMEOUT).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            java.util.List<String> names = jaegerClient.listSpanNames(SERVICE_NAME);
            System.out.println("Span names in Jaeger: " + names);
            assertThat(names)
                    .as("Expected span names not to be empty")
                    .isNotEmpty();
            Map<String, List<JsonNode>> byScope =
                    jaegerClient.findHttpSpansByScopeForRoute(SERVICE_NAME, ERROR_ROUTE);
            System.out.println("Spans by scope for route: " + byScope);
            
            assertThat(byScope).isNotEmpty();
            
            // Проверяем, что спан от Java Agent содержит statusCode = 2 (ERROR)
            List<JsonNode> agentSpans = byScope.entrySet().stream()
                    .filter(e -> e.getKey() != null && e.getKey().contains("io.opentelemetry.tomcat"))
                    .map(Map.Entry::getValue)
                    .flatMap(List::stream)
                    .toList();
                    
            assertThat(agentSpans).isNotEmpty();
            
            JsonNode agentSpan = agentSpans.get(0);
            // 2 means ERROR in otlp proto representation
            int statusCode = agentSpan.path("status").path("code").asInt(0);
            assertThat(statusCode).isEqualTo(2);
        });
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
