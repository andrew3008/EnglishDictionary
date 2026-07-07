package space.br1440.platform.tracing.e2e.smoke;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import space.br1440.platform.tracing.e2e.support.JaegerTestContainerSupport;
import space.br1440.platform.tracing.e2e.support.JaegerV3QueryClient;
import space.br1440.platform.tracing.e2e.support.LongLivedAgentSmokeProcess;

import java.io.File;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Live smoke семантики runtime-управления sampling'ом (PR-C; ADR-runtime-sampling-policy).
 * <p>
 * Дополняет существующее покрытие:
 * <ul>
 *   <li>{@code ForceSamplingAgentSmokeTest} — force header при <b>startup</b>-ratio 0;</li>
 *   <li>перф-гейт M10 — reload под нагрузкой не деградирует p99/queue (но не проверяет
 *       корректность самого решения).</li>
 * </ul>
 * Здесь доказывается именно <b>семантика</b> runtime-смены: смена ratio через JMX
 * (in-process MBean, без рестарта child-JVM) реально меняет export rate, а {@code X-Trace-On}
 * после смены продолжает пробивать ratio 0.0 (приоритет {@code ForceHeaderRule} над
 * ratio-правилами — порядок цепочки из ADR, раздел 2.1).
 * <p>
 * Детерминизм assert'ов: {@code traceIdRatioBased(1.0)} сэмплирует все span'ы,
 * {@code traceIdRatioBased(0.0)} — никакие (ADR, раздел 2.3), поэтому счётчики точные,
 * без вероятностных допусков.
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "Smoke-тесты пропущены через -DskipE2e=true")
class RuntimeSamplingControlSmokeTest {

    private static final String SERVICE_NAME = "runtime-sampling-control-smoke";
    private static final int REQUESTS_PER_PHASE = 5;
    private static final Duration TRACE_VISIBILITY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(3);

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
    void runtime_смена_ratio_меняет_export_rate_а_force_header_пробивает_ratio_0() throws Exception {
        int httpPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            httpPort = socket.getLocalPort();
        }

        try (LongLivedAgentSmokeProcess app = LongLivedAgentSmokeProcess.start(
                RuntimeSamplingControlSmokeMain.class.getName(),
                otelAgentJar,
                testRuntimeClasspath,
                otlpEndpoint,
                SERVICE_NAME,
                httpPort,
                extensionJar,
                List.of(
                        "platform.tracing.sampling.ratio=1.0",
                        "platform.tracing.suppression.suppress-micrometer-tracing=true",
                        "otel.bsp.schedule.delay=200"),
                READY_TIMEOUT,
                3_000L)) {

            // --- Фаза 1: startup-ratio 1.0 — каждый запрос даёт экспортируемый SERVER-span.
            for (int i = 0; i < REQUESTS_PER_PHASE; i++) {
                assertThat(app.httpGet("/phase1", Map.of())).isEqualTo(200);
            }

            // --- Runtime-смена ratio 1.0 -> 0.0 через JMX (без рестарта процесса).
            LongLivedAgentSmokeProcess.PostResult update =
                    app.httpPost("/admin/sampling-ratio?value=0.0");
            assertThat(update.code())
                    .as("Смена ratio через MBean должна пройти. Body: %s; output:\n%s",
                            update.body(), app.output())
                    .isEqualTo(200);
            // Версия конфигурации выросла => апдейт принят agent-side holder'ом, а не потерян
            // (инвариант C-6 ADR: каждый принятый апдейт версионирован).
            assertThat(update.body()).contains("ratio=0.0").contains("version=");

            // --- Фаза 2: ratio 0.0 — обычные запросы детерминированно дропаются...
            for (int i = 0; i < REQUESTS_PER_PHASE; i++) {
                assertThat(app.httpGet("/phase2", Map.of())).isEqualTo(200);
            }
            // ...а X-Trace-On обязан пробить ratio 0.0 (ForceHeaderRule стоит в цепочке
            // до RouteRatio/DefaultRatio — ратифицировано ADR, раздел 2.1).
            assertThat(app.httpGet("/phase2", Map.of("X-Trace-On", "on"))).isEqualTo(200);

            // --- Завершение: graceful shutdown с flush BSP.
            assertThat(app.httpPost("/admin/shutdown").code()).isEqualTo(200);
            int exitCode = app.awaitExit(Duration.ofMinutes(2));
            assertThat(exitCode)
                    .as("Smoke JVM exit code. Output:\n%s", app.output())
                    .isZero();
            assertThat(app.output())
                    .doesNotContain("OpenTelemetry Javaagent failed to start");

            await().atMost(TRACE_VISIBILITY_TIMEOUT)
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        List<String> spanNames = jaegerClient.listSpanNames(SERVICE_NAME);

                        long phase1Count = spanNames.stream()
                                .filter(name -> name.contains("/phase1")).count();
                        long phase2Count = spanNames.stream()
                                .filter(name -> name.contains("/phase2")).count();

                        // Фаза 1 (ratio 1.0): все запросы экспортированы.
                        assertThat(phase1Count)
                                .as("ratio=1.0: каждый из %s запросов /phase1 экспортируется; spanNames=%s",
                                        REQUESTS_PER_PHASE, spanNames)
                                .isEqualTo(REQUESTS_PER_PHASE);

                        // Фаза 2 (runtime ratio 0.0): экспортирован РОВНО один span —
                        // форсированный заголовком; остальные дропнуты детерминированно.
                        assertThat(phase2Count)
                                .as("ratio=0.0 (runtime): только X-Trace-On запрос экспортируется; spanNames=%s",
                                        spanNames)
                                .isEqualTo(1);

                        Optional<Map<String, String>> forced = jaegerClient.findFirstSpanAttributes(
                                SERVICE_NAME,
                                attrs -> attrs.getOrDefault("url.path", "").contains("/phase2"));
                        assertThat(forced).isPresent();
                        assertThat(forced.get().get("platform.sampling.reason"))
                                .as("Единственный span фазы 2 — форсированный (reason=force_header)")
                                .isEqualTo("force_header");
                    });
        }
    }
}
