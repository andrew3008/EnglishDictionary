package space.br1440.platform.tracing.e2e.smoke;

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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Live smoke: force sampling через {@code X-Trace-On} при {@code platform.tracing.sampling.ratio=0}.
 * <p>
 * Подтверждает OTel Semconv нормализацию заголовка ({@code x_trace_on}) и работу
 * {@code otel.instrumentation.http.server.capture-request-headers} с extension {@code CompositeSampler}.
 * Дочерняя JVM — минимальный Spring Boot Web (spring-webmvc), HTTP-запрос из JUnit через OkHttp.
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "Smoke-тесты пропущены через -DskipE2e=true")
class ForceSamplingAgentSmokeTest {

    private static final String SERVICE_NAME = "force-sampling-agent-smoke";
    private static final Duration TRACE_VISIBILITY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AGENT_PROCESS_TIMEOUT = Duration.ofMinutes(3);

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
    void agent_force_header_при_ratio_0_записывает_http_span() throws Exception {
        int httpPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            httpPort = socket.getLocalPort();
        }

        String agentOutput = AgentHttpSpringSmokeProcessRunner.run(
                AgentSpringForceSamplingSmokeMain.class.getName(),
                otelAgentJar,
                testRuntimeClasspath,
                otlpEndpoint,
                SERVICE_NAME,
                httpPort,
                extensionJar,
                "/probe",
                Map.of("X-Trace-On", "on"),
                Map.of(),
                List.of(
                        "platform.tracing.sampling.ratio=0",
                        "platform.tracing.suppression.suppress-micrometer-tracing=true",
                        "otel.bsp.schedule.delay=200"),
                AGENT_PROCESS_TIMEOUT,
                8_000L);

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Optional<Map<String, String>> httpSpan = jaegerClient.findFirstSpanAttributes(
                            SERVICE_NAME,
                            attrs -> "force_header".equals(attrs.get("platform.sampling.reason"))
                                    || attrs.containsKey("url.path"));
                    assertThat(httpSpan)
                            .as("HTTP span с force header должен попасть в Jaeger при ratio=0; agentOutput:\n%s\nspanNames=%s",
                                    agentOutput, jaegerClient.listSpanNames(SERVICE_NAME))
                            .isPresent();
                    assertThat(httpSpan.get().get("platform.sampling.reason"))
                            .as("Context-first CompositeSampler выставляет force_header при X-Trace-On")
                            .isEqualTo("force_header");
                });
    }

}
