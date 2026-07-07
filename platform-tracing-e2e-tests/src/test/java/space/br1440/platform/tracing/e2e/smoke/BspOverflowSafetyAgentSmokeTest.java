package space.br1440.platform.tracing.e2e.smoke;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E safety smoke для BSP overflow: дочерняя JVM с OTel Java Agent, маленькой BSP-очередью
 * и недоступным OTLP endpoint. Цель — доказать эксплуатационную безопасность под
 * переполнением экспорта, <b>а не</b> порядок drop'ов (см.
 * {@code docs/decisions/ADR-bsp-overflow-policy-finding.md} → finding outcome 2: drop-new).
 *
 * <p><b>Primary scenario:</b> unavailable OTLP endpoint (connection refused) — самый дешёвый
 * способ воспроизвести «pipeline недоступен» без Docker collector. Slow-collector сценарий
 * — secondary (через Testcontainers), здесь не реализован.</p>
 *
 * <p><b>Assertions (must-have):</b></p>
 * <ul>
 *   <li>subprocess завершился за timeout;</li>
 *   <li>exit code == 0;</li>
 *   <li>output содержит probe-marker {@code ALIVE_AFTER_SPAM=true};</li>
 *   <li>output содержит probe-marker {@code ALIVE_AFTER_HOLD=true};</li>
 *   <li>output не содержит маркеров критических ошибок (OutOfMemoryError, StackOverflowError).</li>
 * </ul>
 *
 * <p><b>Не валидируется:</b> heap usage (soft sanity, flaky), точный текст логов SDK
 * (может меняться при upgrade), точное число dropped spans.</p>
 */
class BspOverflowSafetyAgentSmokeTest {

    private static final String SERVICE_NAME = "bsp-overflow-safety-smoke";

    /** Размер очереди — намеренно крошечный для гарантированного overflow. */
    private static final int MAX_QUEUE = 4;
    private static final int MAX_BATCH = 2;
    /** Количество span'ов, испускаемых в спам-фазе. */
    private static final int SPAM_SPANS = 5_000;
    /** Пауза после спам-фазы — дать background retry'ям сработать. */
    private static final long POST_SPAM_HOLD_MS = 3_000L;

    /**
     * Заведомо недоступный endpoint: 127.0.0.1:1 (connection refused моментально).
     * Не зависит от Docker и не требует Testcontainers.
     */
    private static final String UNAVAILABLE_OTLP_ENDPOINT = "http://127.0.0.1:1";

    private static String otelAgentJar;
    private static String testRuntimeClasspath;

    @BeforeAll
    static void setUp() {
        otelAgentJar = System.getProperty("otel.javaagent.jar");
        testRuntimeClasspath = System.getProperty("smoke.test.runtime.classpath");

        assertThat(otelAgentJar).as("System property otel.javaagent.jar").isNotBlank();
        assertThat(testRuntimeClasspath).as("System property smoke.test.runtime.classpath").isNotBlank();
        assertThat(new File(otelAgentJar)).exists().isFile();
    }

    @Test
    @DisplayName("Stock BSP (без extension): приложение живо при overflow + недоступный OTLP (exit 0)")
    void appStaysAliveOnOverflowAndUnavailableEndpoint() throws Exception {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");

        List<String> jvmProperties = new ArrayList<>();
        jvmProperties.add("otel.service.name=" + SERVICE_NAME);
        jvmProperties.add("otel.traces.exporter=otlp");
        jvmProperties.add("otel.exporter.otlp.protocol=grpc");
        jvmProperties.add("otel.exporter.otlp.endpoint=" + UNAVAILABLE_OTLP_ENDPOINT);
        jvmProperties.add("otel.metrics.exporter=none");
        jvmProperties.add("otel.logs.exporter=none");
        // BSP overflow: маленькая очередь + быстрый schedule + всегда сэмплируем.
        jvmProperties.add("otel.bsp.max.queue.size=" + MAX_QUEUE);
        jvmProperties.add("otel.bsp.max.export.batch.size=" + MAX_BATCH);
        jvmProperties.add("otel.bsp.schedule.delay=50");
        jvmProperties.add("otel.bsp.export.timeout=500");
        jvmProperties.add("otel.traces.sampler=always_on");

        List<String> command = new ArrayList<>();
        command.add(javaBin.toString());
        for (String property : jvmProperties) {
            command.add("-D" + property);
        }
        command.add("-javaagent:" + otelAgentJar);
        command.add("-cp");
        command.add(testRuntimeClasspath);
        command.add(BspOverflowSafetyMain.class.getName());
        command.add(Integer.toString(SPAM_SPANS));
        command.add(Long.toString(POST_SPAM_HOLD_MS));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);

        assertThat(finished)
                .as("Subprocess must finish in 60s. Output:\n%s", output)
                .isTrue();
        assertThat(process.exitValue())
                .as("Subprocess exit code must be 0. Output:\n%s", output)
                .isZero();
        assertThat(output)
                .as("Probe 'alive after spam' marker required. Output:\n%s", output)
                .contains("ALIVE_AFTER_SPAM=true spans=" + SPAM_SPANS);
        assertThat(output)
                .as("Probe 'alive after hold' marker required. Output:\n%s", output)
                .contains("ALIVE_AFTER_HOLD=true");
        // Жёсткий guard — критические JVM-ошибки, которые точно означают потерю безопасности.
        assertThat(output)
                .as("No OOM/StackOverflow under overflow. Output:\n%s", output)
                .doesNotContain("OutOfMemoryError")
                .doesNotContain("StackOverflowError");
        // OTel Agent должен стартовать.
        assertThat(output)
                .as("OTel Agent must start cleanly. Output:\n%s", output)
                .doesNotContain("OpenTelemetry Javaagent failed to start");
    }
}
