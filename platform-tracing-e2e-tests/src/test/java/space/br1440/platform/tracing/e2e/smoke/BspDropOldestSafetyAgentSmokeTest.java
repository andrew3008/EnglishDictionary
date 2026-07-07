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
 * E2E safety smoke для opt-in {@code platform.tracing.queue.overflow-policy=DROP_OLDEST}:
 * дочерняя JVM с OTel Java Agent + платформенным extension'ом + недоступным OTLP endpoint.
 *
 * <p><b>Цель</b> (обязательный gate v1.x):</p>
 * <ol>
 *   <li>при активированном opt-in DROP_OLDEST приложение остаётся живо при недоступном OTLP;</li>
 *   <li>платформенный extension эмитит INFO «Platform DROP_OLDEST export processor enabled»;</li>
 *   <li>OTel Agent стартует чисто, нет OOM/StackOverflow, exit code 0.</li>
 * </ol>
 *
 * <p><b>Зеркальный к</b> {@link BspOverflowSafetyAgentSmokeTest}: тот валидирует default
 * v1.x (stock BSP), этот — opt-in путь. Оба обязательны и выполняются параллельно как
 * отдельные gates в CI с {@code -PrunE2e}.</p>
 *
 * <p>No-double-export проверяется <b>отдельным</b> тестом
 * {@link BspDropOldestNoDoubleExportTest} — здесь это технически невозможно совместить с
 * unavailable OTLP сценарием (нет потока с реального collector'а для подсчёта).</p>
 */
class BspDropOldestSafetyAgentSmokeTest {

    private static final String SERVICE_NAME = "bsp-drop-oldest-safety-smoke";

    private static final int MAX_QUEUE = 4;
    private static final int MAX_BATCH = 2;
    private static final int SPAM_SPANS = 5_000;
    private static final long POST_SPAM_HOLD_MS = 3_000L;

    private static final String UNAVAILABLE_OTLP_ENDPOINT = "http://127.0.0.1:1";

    private static String otelAgentJar;
    private static String extensionJar;
    private static String testRuntimeClasspath;

    @BeforeAll
    static void setUp() {
        otelAgentJar = System.getProperty("otel.javaagent.jar");
        extensionJar = System.getProperty("smoke.otel.extension.jar");
        testRuntimeClasspath = System.getProperty("smoke.test.runtime.classpath");

        assertThat(otelAgentJar).as("System property otel.javaagent.jar").isNotBlank();
        assertThat(extensionJar).as("System property smoke.otel.extension.jar").isNotBlank();
        assertThat(testRuntimeClasspath).as("System property smoke.test.runtime.classpath").isNotBlank();
        assertThat(new File(otelAgentJar)).exists().isFile();
        assertThat(new File(extensionJar)).exists().isFile();
    }

    @Test
    @DisplayName("Opt-in DROP_OLDEST: приложение живо при overflow + INFO маркер opt-in")
    void optInDropOldestStaysAliveAndLogsActivation() throws Exception {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");

        List<String> jvmProperties = new ArrayList<>();
        jvmProperties.add("otel.service.name=" + SERVICE_NAME);
        jvmProperties.add("otel.traces.exporter=otlp");
        jvmProperties.add("otel.exporter.otlp.protocol=grpc");
        jvmProperties.add("otel.exporter.otlp.endpoint=" + UNAVAILABLE_OTLP_ENDPOINT);
        jvmProperties.add("otel.metrics.exporter=none");
        jvmProperties.add("otel.logs.exporter=none");
        jvmProperties.add("otel.bsp.max.queue.size=" + MAX_QUEUE);
        jvmProperties.add("otel.bsp.max.export.batch.size=" + MAX_BATCH);
        jvmProperties.add("otel.bsp.schedule.delay=50");
        jvmProperties.add("otel.bsp.export.timeout=500");
        jvmProperties.add("otel.traces.sampler=always_on");
        // Активация opt-in замены стандартного BSP на платформенный DROP_OLDEST processor.
        jvmProperties.add("platform.tracing.queue.overflow-policy=DROP_OLDEST");

        List<String> command = new ArrayList<>();
        command.add(javaBin.toString());
        for (String property : jvmProperties) {
            command.add("-D" + property);
        }
        command.add("-javaagent:" + otelAgentJar);
        // Платформенный extension подгружается в classloader Agent'а.
        command.add("-Dotel.javaagent.extensions=" + extensionJar);
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
        // Opt-in активация подтверждается имени потока {@code platform-tracing-drop-oldest-*},
        // которое попадает в Agent's logger вывод (формат: [<thread-name>] ...). Это более
        // надёжный маркер, чем SLF4J-лог extension'а: в Agent-classloader'е extension не имеет
        // SLF4J binding и его log.info уходит в NOP-logger. Само же наличие потока — прямое
        // доказательство того, что PlatformDropOldestExportSpanProcessor был создан и
        // запущен customizer'ом (см. ADR-drop-oldest-export-processor-v1.md).
        assertThat(output)
                .as("Platform DROP_OLDEST processor thread marker required (opt-in activation). Output:\n%s", output)
                .contains("platform-tracing-drop-oldest");
        // Жёсткие safety guards.
        assertThat(output)
                .as("No OOM/StackOverflow under overflow with opt-in. Output:\n%s", output)
                .doesNotContain("OutOfMemoryError")
                .doesNotContain("StackOverflowError");
        assertThat(output)
                .as("OTel Agent must start cleanly with platform extension. Output:\n%s", output)
                .doesNotContain("OpenTelemetry Javaagent failed to start");
    }
}
