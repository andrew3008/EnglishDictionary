package space.br1440.platform.tracing.e2e.smoke;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;

import java.io.File;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resilience-контракт Фазы 16 (теория §«Что тестировать»): сервис обязан работать
 * при полностью недоступном Collector'е.
 * <p>
 * Дочерняя JVM — Spring Boot + OTel Java Agent + platform-extension, OTLP endpoint
 * указывает на гарантированно закрытый порт ({@code http://127.0.0.1:1}). Проверяется
 * интеграционно (на уровне процесса) то, что Фазы 10/11 гарантируют на уровне юнитов:
 * <ol>
 *   <li>приложение стартует и отвечает на HTTP, несмотря на недоступный экспорт;</li>
 *   <li>latency бизнес-запроса не деградирует (экспорт не блокирует hot-path);</li>
 *   <li>процесс завершается gracefully (exit 0), без OOM/StackOverflow;</li>
 *   <li>OTel Agent не падает на старте.</li>
 * </ol>
 * Docker для этого теста НЕ нужен (Collector намеренно отсутствует), но тест gated
 * вместе с остальными e2e ({@code -PrunE2e}) — он запускает дочернюю JVM.
 */
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "E2E-тесты пропущены через -DskipE2e=true")
class CollectorUnavailableResilienceTest {

    private static final String SERVICE_NAME = "collector-unavailable-resilience";
    private static final Duration AGENT_PROCESS_TIMEOUT = Duration.ofMinutes(3);
    /**
     * SLA probe-запроса при мёртвом Collector'е. Запас над типичной локальной latency
     * (десятки ms): экспорт асинхронный (BSP-очередь), отказ соединения не должен
     * проникать в путь запроса.
     */
    private static final Duration PROBE_LATENCY_SLA = Duration.ofSeconds(2);

    /** Гарантированно недоступный OTLP endpoint: порт 1 (reserved, никогда не слушается). */
    private static final String DEAD_COLLECTOR_ENDPOINT = "http://127.0.0.1:1";

    private static String testRuntimeClasspath;
    private static String otelAgentJar;
    private static String extensionJar;

    @BeforeAll
    static void setUp() {
        otelAgentJar = System.getProperty("otel.javaagent.jar");
        extensionJar = System.getProperty("smoke.otel.extension.jar");
        testRuntimeClasspath = System.getProperty("smoke.test.runtime.classpath");

        assertThat(otelAgentJar).isNotBlank();
        assertThat(extensionJar).isNotBlank();
        assertThat(testRuntimeClasspath).isNotBlank();
        assertThat(new File(otelAgentJar)).exists().isFile();
        assertThat(new File(extensionJar)).exists().isFile();
    }

    @Test
    void приложение_работает_при_недоступном_collector() throws Exception {
        int httpPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            httpPort = socket.getLocalPort();
        }

        // Runner сам ассертит: READY за timeout, probe 2xx, graceful exit 0, Agent стартовал.
        // flushDelay минимальный: ждать flush в мёртвый endpoint бессмысленно,
        // важно лишь, что shutdown не зависает (asserted через processTimeout).
        AgentHttpSpringSmokeProcessRunner.RunResult result = AgentHttpSpringSmokeProcessRunner.runMeasured(
                AgentSpringForceSamplingSmokeMain.class.getName(),
                otelAgentJar,
                testRuntimeClasspath,
                DEAD_COLLECTOR_ENDPOINT,
                SERVICE_NAME,
                httpPort,
                extensionJar,
                "/probe",
                Map.of("X-Trace-On", "on"),
                Map.of(),
                List.of(
                        // Production-подобный профиль: сэмплируем всё, экспорт упирается в отказ.
                        "platform.tracing.sampling.ratio=1",
                        "platform.tracing.suppression.suppress-micrometer-tracing=true",
                        // Короткий export timeout: быстрее проявляет блокировки, если они есть.
                        "otel.exporter.otlp.timeout=1000"),
                AGENT_PROCESS_TIMEOUT,
                500L);

        assertThat(result.probeLatency())
                .as("запрос обязан отвечать в пределах SLA при недоступном Collector'е "
                        + "(экспорт не должен блокировать hot-path)")
                .isLessThan(PROBE_LATENCY_SLA);

        assertThat(result.output())
                .as("отказ экспорта не должен приводить к фатальным ошибкам приложения")
                .doesNotContain("OutOfMemoryError")
                .doesNotContain("StackOverflowError");
    }
}
