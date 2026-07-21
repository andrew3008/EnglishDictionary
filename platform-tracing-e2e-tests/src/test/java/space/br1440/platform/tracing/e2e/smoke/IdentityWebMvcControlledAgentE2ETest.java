package space.br1440.platform.tracing.e2e.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;
import space.br1440.platform.tracing.e2e.support.JaegerTestContainerSupport;
import space.br1440.platform.tracing.e2e.support.JaegerV3QueryClient;

@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true")
class IdentityWebMvcControlledAgentE2ETest {

    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(3);
    private static GenericContainer<?> jaeger;
    private static JaegerV3QueryClient jaegerClient;
    private static String otlpEndpoint;
    private static String classpath;
    private static String controlledAgent;

    @BeforeAll
    static void startInfrastructure() {
        controlledAgent = System.getProperty("smoke.controlled.agent.jar");
        classpath = System.getProperty("smoke.test.runtime.classpath");
        assertThat(new File(controlledAgent)).isFile();
        jaeger = JaegerTestContainerSupport.newJaeger();
        jaeger.start();
        otlpEndpoint = JaegerTestContainerSupport.otlpHttpEndpoint(jaeger);
        jaegerClient = new JaegerV3QueryClient(JaegerTestContainerSupport.queryBaseUrl(jaeger));
    }

    @AfterAll
    static void stopInfrastructure() {
        if (jaeger != null) {
            jaeger.stop();
        }
    }

    @Test
    void preservesAndGeneratesRequestIdentityWithFailClosedCorrelationProjection() throws Exception {
        String preservedService = "slice-m-webmvc-preserved";
        String preservedOutput = run(preservedService, Map.of(
                "X-Request-Id", "request-preserved",
                "X-Correlation-ID", "spoofed-header",
                "baggage", "platform.correlation.id=spoofed-baggage"));
        assertThat(preservedOutput)
                .contains("IDENTITY_WEBMVC:requestId=request-preserved")
                .contains("IDENTITY_WEBMVC:spoofRejected=true")
                .contains("IDENTITY_WEBMVC:localCorrelation=local-webmvc-correlation")
                .contains("IDENTITY_WEBMVC:correlationAfterScope=empty")
                .contains("IDENTITY_WEBMVC:openTelemetryBeans=0");

        assertProjectedSpans(preservedService, "request-preserved");

        String generatedService = "slice-m-webmvc-generated";
        String generatedOutput = run(generatedService, Map.of());
        assertThat(generatedOutput)
                .contains("IDENTITY_WEBMVC:spoofRejected=true")
                .contains("IDENTITY_WEBMVC:openTelemetryBeans=0")
                .doesNotContain("IDENTITY_WEBMVC:requestId=request-preserved");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, String> attributes = jaegerClient.findFirstSpanAttributes(
                    generatedService, values -> values.containsKey("platform.request_id")).orElseThrow();
            assertThat(attributes.get("platform.request_id")).matches("[0-9a-f-]{36}");
        });
    }

    private static String run(String serviceName, Map<String, String> headers) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        return AgentHttpSpringSmokeProcessRunner.run(
                IdentityWebMvcSmokeMain.class.getName(),
                controlledAgent,
                classpath,
                otlpEndpoint,
                serviceName,
                port,
                null,
                "/identity",
                headers,
                Map.of(),
                List.of("otel.traces.sampler=platform", "platform.tracing.sampling.ratio=1"),
                PROCESS_TIMEOUT,
                3_000L);
    }

    private static void assertProjectedSpans(String serviceName, String requestId) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(jaegerClient.serverTraceIdsForRoute(serviceName, "/identity")).hasSize(1);
            Map<String, String> server = jaegerClient.findFirstSpanAttributes(
                    serviceName, values -> requestId.equals(values.get("platform.request_id"))).orElseThrow();
            assertThat(server).doesNotContainKey("platform.correlation_id");
            Map<String, String> child = jaegerClient.findSpanAttributesByName(
                    serviceName, "identity-local-child").orElseThrow();
            assertThat(child).containsEntry("platform.correlation_id", "local-webmvc-correlation");
            assertThat(child).doesNotContainKey("baggage.platform.correlation.id");
        });
    }
}
