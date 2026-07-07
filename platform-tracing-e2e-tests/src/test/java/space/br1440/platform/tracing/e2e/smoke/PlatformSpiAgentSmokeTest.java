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
 * Live smoke (Фаза 15): named SPI расширения видны OTel Java Agent через {@code ExtensionClassLoader}.
 * <p>
 * Дочерняя JVM запускается с {@code -Dotel.traces.sampler=platform} и
 * {@code -Dotel.propagators=tracecontext,baggage,platform-trace-control} — то есть платформенные
 * sampler/propagator резолвятся <b>по имени</b> через {@code ConfigurableSamplerProvider}/
 * {@code ConfigurablePropagatorProvider} (а не через inline-customizer'ы). При {@code ratio=0} и
 * входящем {@code X-Trace-On} span обязан записаться с {@code platform.sampling.reason=force_header}:
 * <ul>
 *   <li>факт записи при ratio=0 ⇒ named {@code platform}-sampler ({@code CompositeSampler}) реально активен;</li>
 *   <li>{@code force_header} ⇒ named {@code platform-trace-control} propagator извлёк {@code X-Trace-On} в Context.</li>
 * </ul>
 * Тест — fail-fast детектор риска «Agent не вызывает ServiceLoader для SPI из ExtensionClassLoader»
 * (см. ADR-classloader-visibility-spike-finding, otel-compatibility-matrix.md §Extension SPI).
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "Smoke-тесты пропущены через -DskipE2e=true")
class PlatformSpiAgentSmokeTest {

    private static final String SERVICE_NAME = "platform-spi-agent-smoke";
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
    void named_sampler_и_propagator_резолвятся_через_agent_spi() throws Exception {
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
                        // Named SPI резолвятся по имени — не через inline-customizer.
                        "otel.traces.sampler=platform",
                        "otel.propagators=tracecontext,baggage,platform-trace-control",
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
                            .as("При ratio=0 named platform-sampler должен записать span по X-Trace-On; agentOutput:\n%s\nspanNames=%s",
                                    agentOutput, jaegerClient.listSpanNames(SERVICE_NAME))
                            .isPresent();
                    assertThat(httpSpan.get().get("platform.sampling.reason"))
                            .as("named platform-trace-control propagator + platform sampler → force_header")
                            .isEqualTo("force_header");
                });
    }
}
