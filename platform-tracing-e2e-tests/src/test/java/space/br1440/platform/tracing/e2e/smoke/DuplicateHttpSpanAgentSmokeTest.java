package space.br1440.platform.tracing.e2e.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
 * DupSpans HTTP smoke (P1 recommended перед broad rollout).
 *
 * <p><b>Primary gate (must-have):</b> subprocess с намеренно сломанной конфигурацией
 * (Agent on + {@code suppress-micrometer-tracing=false}) логирует startup-WARN от
 * {@code TracingObservationSuppressStartupRunner} о риске дублирования HTTP-span'ов.
 * Это стабильный e2e-контракт, не зависящий от того, экспортирует ли Micrometer bridge
 * отдельный span в Jaeger при premain Agent (на практике часто виден только Agent-scope).</p>
 *
 * <p><b>Secondary gate (best-effort):</b> если в Jaeger появились span'ы от {@code /probe},
 * фиксируем их для диагностики. Требование «>= 2 instrumentation scopes в Jaeger» не является
 * жёстким gate — см. {@code DuplicateSpansRegressionMatrixTest} (startup bean-контракт)
 * и этот WARN-smoke.</p>
 *
 * <p>Classpath subprocess включает {@code micrometer-tracing-bridge-otel} (dev/staging path).</p>
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "Smoke-тесты пропущены через -DskipE2e=true")
class DuplicateHttpSpanAgentSmokeTest {

    private static final String SERVICE_NAME_BROKEN = "dup-http-span-smoke-broken";
    private static final String SERVICE_NAME_OK = "dup-http-span-smoke-ok";
    private static final String ROUTE = "/probe";
    private static final Duration TRACE_VISIBILITY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(3);

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
    @DisplayName("Agent on + suppress=false → startup WARN о дублировании HTTP-span'ов (subprocess)")
    void agent_on_suppress_off_logs_duplicate_spans_WARN() throws Exception {
        int httpPort = freePort();

        String output = AgentHttpSpringSmokeProcessRunner.run(
                DuplicateHttpSpanSmokeMain.class.getName(),
                otelAgentJar,
                testRuntimeClasspath,
                otlpEndpoint,
                SERVICE_NAME_BROKEN,
                httpPort,
                extensionJar,
                "/probe",
                null,
                Map.of("OTEL_TRACES_SAMPLER", "always_on"),
                List.of(
                        "otel.traces.sampler=platform",
                        "platform.tracing.sampling.ratio=1.0",
                        "otel.bsp.schedule.delay=200"),
                PROCESS_TIMEOUT,
                3_000L);

        assertThat(output)
                .as("Намеренно сломанная конфигурация должна логировать WARN о дублировании HTTP-span'ов")
                .contains("дублирование HTTP-span")
                .contains("suppress-micrometer-tracing=false");

        // Best-effort: HTTP span от Agent должен появиться в Jaeger (pipeline жив).
        await().atMost(TRACE_VISIBILITY_TIMEOUT).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            Map<String, List<JsonNode>> byScope =
                    jaegerClient.findHttpSpansByScopeForRoute(SERVICE_NAME_BROKEN, ROUTE);
            assertThat(byScope)
                    .as("Хотя бы один HTTP-span на /probe должен дойти до Jaeger. spanNames=%s",
                            jaegerClient.listSpanNames(SERVICE_NAME_BROKEN))
                    .isNotEmpty();
        });
    }

    @Test
    @DisplayName("Agent on + suppress=true → без WARN о дублировании (контрольный subprocess)")
    void agent_on_suppress_on_no_duplicate_WARN() throws Exception {
        int httpPort = freePort();

        String output = AgentHttpSpringSmokeProcessRunner.run(
                AgentSpringForceSamplingSmokeMain.class.getName(),
                otelAgentJar,
                testRuntimeClasspath,
                otlpEndpoint,
                SERVICE_NAME_OK,
                httpPort,
                extensionJar,
                "/probe",
                null,
                Map.of("OTEL_TRACES_SAMPLER", "always_on"),
                List.of(
                        "otel.traces.sampler=platform",
                        "platform.tracing.sampling.ratio=1.0",
                        "otel.bsp.schedule.delay=200"),
                PROCESS_TIMEOUT,
                3_000L);

        assertThat(output)
                .as("Штатная prod-конфигурация (suppress=true) не должна WARN'ить о дублировании")
                .doesNotContain("дублирование HTTP-span");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
