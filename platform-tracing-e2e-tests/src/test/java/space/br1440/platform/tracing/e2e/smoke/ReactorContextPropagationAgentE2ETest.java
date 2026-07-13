package space.br1440.platform.tracing.e2e.smoke;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import space.br1440.platform.tracing.e2e.support.AgentWebFluxProcessRunner;
import space.br1440.platform.tracing.e2e.support.JaegerTestContainerSupport;
import space.br1440.platform.tracing.e2e.support.JaegerV3QueryClient;

import java.io.File;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * G2-05-e2e: OTel Context и {@code platform.remote.service} сохраняются после {@code publishOn}
 * в subprocess Spring WebFlux + OTel Java Agent (production path).
 * <p>
 * Source of truth для sign-off WebFlux propagation. In-process {@code @Tag("bridge-otel-path")}
 * тесты не заменяют этот сценарий.
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "Smoke-тесты пропущены через -DskipE2e=true")
class ReactorContextPropagationAgentE2ETest {

    private static final String SERVICE_NAME = "reactor-context-propagation-agent-e2e";
    private static final Duration TRACE_VISIBILITY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AGENT_PROCESS_TIMEOUT = Duration.ofMinutes(3);
    private static final String E2E_REMOTE_SERVICE = "upstream-e2e-g205";

    private static GenericContainer<?> jaeger;
    private static JaegerV3QueryClient jaegerClient;
    private static String otlpEndpoint;
    private static String testRuntimeClasspath;
    private static String otelAgentJar;
    private static String extensionJar;

    @BeforeAll
    static void setUpStack() {
        otelAgentJar = System.getProperty("otel.javaagent.jar");
        extensionJar = System.getProperty("smoke.otel.extension.jar");
        testRuntimeClasspath = System.getProperty("smoke.test.runtime.classpath");

        assertThat(otelAgentJar).isNotBlank();
        assertThat(extensionJar).isNotBlank();
        assertThat(testRuntimeClasspath).isNotBlank();
        assertThat(new File(otelAgentJar)).exists().isFile();
        assertThat(new File(extensionJar)).exists().isFile();

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
    void agent_webflux_publishOn_сохраняет_traceId_и_remote_service() throws Exception {
        int httpPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            httpPort = socket.getLocalPort();
        }

        String responseBody = AgentWebFluxProcessRunner.runPropagationTest(
                otelAgentJar,
                testRuntimeClasspath,
                otlpEndpoint,
                SERVICE_NAME,
                httpPort,
                extensionJar,
                Map.of(),
                List.of(
                        "platform.tracing.sampling.ratio=1.0",
                        "platform.tracing.suppression.suppress-micrometer-tracing=true",
                        "spring.reactor.context-propagation=AUTO",
                        "otel.bsp.schedule.delay=200"),
                AGENT_PROCESS_TIMEOUT,
                8_000L);

        String[] parts = responseBody.split("\\|", -1);
        assertThat(parts).as("Формат ответа /propagation-test").hasSize(4);

        String callerTraceId = parts[0];
        String workerTraceId = parts[1];
        String workerRemoteService = parts[2];
        String workerThread = parts[3];

        assertThat(callerTraceId).isNotBlank();
        assertThat(workerTraceId).isEqualTo(callerTraceId);
        assertThat(workerRemoteService).isEqualTo(E2E_REMOTE_SERVICE);
        assertThat(workerThread).contains("parallel");

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Optional<Map<String, String>> httpSpan = jaegerClient.findFirstSpanAttributes(
                            SERVICE_NAME,
                            attrs -> attrs.containsKey("url.path")
                                    || attrs.containsKey("http.route"));
                    assertThat(httpSpan)
                            .as("HTTP span WebFlux должен попасть в Jaeger; response=%s", responseBody)
                            .isPresent();
                });
    }
}
