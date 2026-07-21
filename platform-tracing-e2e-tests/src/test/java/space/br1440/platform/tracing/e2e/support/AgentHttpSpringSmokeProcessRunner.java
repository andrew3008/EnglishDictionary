package space.br1440.platform.tracing.e2e.support;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Универсальный launcher дочерней JVM со Spring Boot smoke-приложением под OTel Java Agent.
 *
 * <p>Используется обоими HTTP-smoke сценариями:</p>
 * <ul>
 *   <li>{@code ForceSamplingAgentSmokeTest} — force header при ratio=0;</li>
 *   <li>{@code DuplicateHttpSpanAgentSmokeTest} — намеренно сломанная конфигурация
 *       (Agent on + suppress=false) для подсчёта дублирующихся SERVER-span'ов.</li>
 * </ul>
 *
 * <p>Контракт smoke-main:</p>
 * <ol>
 *   <li>args: {@code <port> <flushDelayMs>} (минимум 2; ниже идут пользовательские);</li>
 *   <li>после старта Spring печатает в stdout {@code READY} (одна строка) и flush;</li>
 *   <li>держит контекст до получения HTTP-запроса на {@code /probe} и затем gracefully завершается.</li>
 * </ol>
 *
 * <p>Тест-эндпоинт всегда {@code GET /probe}; заголовки опциональны (например {@code X-Trace-On}).</p>
 */
public final class AgentHttpSpringSmokeProcessRunner {

    /** Контрактный маркер: smoke-main выводит ровно эту строку при готовности Spring. */
    public static final String READY_MARKER = "READY";

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(15))
            .build();

    private AgentHttpSpringSmokeProcessRunner() {
    }

    /**
     * @param mainClassName             FQN smoke-main класса
     * @param otelAgentJar              путь к {@code opentelemetry-javaagent.jar}
     * @param testRuntimeClasspath      classpath child-JVM
     * @param otlpEndpoint              OTLP endpoint (http://host:port)
     * @param serviceName               {@code otel.service.name}
     * @param httpPort                  порт smoke-приложения
     * @param extensionLocation         опционально: путь к extension JAR/каталогу
     * @param requestHeaders            опционально: HTTP-заголовки к {@code /probe}
     * @param extraEnv                  опционально: env vars дочернего процесса
     * @param extraJvmSystemProperties  опционально: дополнительные {@code -D...}
     * @param processTimeout            таймаут на READY и graceful exit
     * @param flushDelayMs              задержка BSP flush в smoke-main
     */
    public static String run(
            String mainClassName,
            String otelAgentJar,
            String testRuntimeClasspath,
            String otlpEndpoint,
            String serviceName,
            int httpPort,
            String extensionLocation,
            String requestRoute,
            Map<String, String> requestHeaders,
            Map<String, String> extraEnv,
            List<String> extraJvmSystemProperties,
            Duration processTimeout,
            long flushDelayMs) throws Exception {
        return runMeasured(mainClassName, otelAgentJar, testRuntimeClasspath, otlpEndpoint,
                serviceName, httpPort, extensionLocation, requestRoute, requestHeaders,
                extraEnv, extraJvmSystemProperties, processTimeout, flushDelayMs).output();
    }

    /**
     * Результат прогона smoke-JVM: полный stdout/stderr процесса и замеренная
     * длительность HTTP probe-запроса (для SLA-ассертов resilience-тестов Фазы 16).
     */
    public record RunResult(String output, Duration probeLatency) {
    }

    /** Вариант {@link #run}, дополнительно замеряющий длительность probe-запроса. */
    public static RunResult runMeasured(
            String mainClassName,
            String otelAgentJar,
            String testRuntimeClasspath,
            String otlpEndpoint,
            String serviceName,
            int httpPort,
            String extensionLocation,
            String requestRoute,
            Map<String, String> requestHeaders,
            Map<String, String> extraEnv,
            List<String> extraJvmSystemProperties,
            Duration processTimeout,
            long flushDelayMs) throws Exception {
        return runMeasured(mainClassName, otelAgentJar, testRuntimeClasspath, otlpEndpoint,
                serviceName, httpPort, extensionLocation, requestRoute, requestHeaders,
                extraEnv, extraJvmSystemProperties, processTimeout, flushDelayMs, true, true);
    }

    public static RunResult runMeasured(
            String mainClassName,
            String otelAgentJar,
            String testRuntimeClasspath,
            String otlpEndpoint,
            String serviceName,
            int httpPort,
            String extensionLocation,
            String requestRoute,
            Map<String, String> requestHeaders,
            Map<String, String> extraEnv,
            List<String> extraJvmSystemProperties,
            Duration processTimeout,
            long flushDelayMs,
            boolean requireAgentStartup) throws Exception {
        return runMeasured(mainClassName, otelAgentJar, testRuntimeClasspath, otlpEndpoint,
                serviceName, httpPort, extensionLocation, requestRoute, requestHeaders,
                extraEnv, extraJvmSystemProperties, processTimeout, flushDelayMs,
                requireAgentStartup, true);
    }

    public static RunResult runMeasured(
            String mainClassName,
            String otelAgentJar,
            String testRuntimeClasspath,
            String otlpEndpoint,
            String serviceName,
            int httpPort,
            String extensionLocation,
            String requestRoute,
            Map<String, String> requestHeaders,
            Map<String, String> extraEnv,
            List<String> extraJvmSystemProperties,
            Duration processTimeout,
            long flushDelayMs,
            boolean requireAgentStartup,
            boolean requireApplicationReady) throws Exception {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");

        List<String> jvmProperties = buildJvmProperties(
                extensionLocation, serviceName, otlpEndpoint, extraJvmSystemProperties);

        List<String> command = new ArrayList<>();
        command.add(javaBin.toString());
        for (String property : jvmProperties) {
            command.add("-D" + property);
        }
        command.add("-javaagent:" + otelAgentJar);
        command.add("-cp");
        command.add(testRuntimeClasspath);
        command.add(mainClassName);
        command.add(Integer.toString(httpPort));
        command.add(Long.toString(flushDelayMs));

        ProcessBuilder builder = new ProcessBuilder(command);
        if (extraEnv != null) {
            builder.environment().putAll(extraEnv);
        }
        builder.redirectErrorStream(true);

        StringBuilder output = new StringBuilder();
        output.append("SMOKE_JVM_PROPERTIES=").append(jvmProperties).append(System.lineSeparator());
        output.append("SMOKE_REQUEST_ROUTE=").append(requestRoute).append(System.lineSeparator());
        output.append("SMOKE_REQUEST_HEADERS=").append(requestHeaders).append(System.lineSeparator());

        Process process = builder.start();

        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            } catch (Exception ignored) {
                // процесс завершился — поток закрыт
            }
        }, "agent-http-smoke-output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean ready = waitForReady(process, output, processTimeout);
        if (!ready && !requireApplicationReady) {
            boolean finished = process.waitFor(processTimeout.toSeconds(), TimeUnit.SECONDS);
            reader.join(5_000L);
            String fullOutput = output.toString();
            assertThat(finished)
                    .as("Fail-closed JVM должна завершиться за %s. Output:\n%s", processTimeout, fullOutput)
                    .isTrue();
            assertThat(process.exitValue())
                    .as("Fail-closed startup обязан завершиться non-zero. Output:\n%s", fullOutput)
                    .isNotZero();
            return new RunResult(fullOutput, Duration.ZERO);
        }
        assertThat(ready)
                .as("Smoke JVM должна вывести %s за %s. Output:\n%s", READY_MARKER, processTimeout, output)
                .isTrue();

        String route = requestRoute != null && !requestRoute.isBlank() ? requestRoute : "/probe";
        Request.Builder requestBuilder = new Request.Builder()
                .url("http://127.0.0.1:" + httpPort + route)
                .get();
        Map<String, String> headers = requestHeaders == null ? new HashMap<>() : requestHeaders;
        headers.forEach(requestBuilder::header);
        long probeStartNanos = System.nanoTime();
        try (Response response = HTTP_CLIENT.newCall(requestBuilder.build()).execute()) {
            if (!"/error".equals(route)) {
                assertThat(response.isSuccessful())
                        .as("HTTP probe к smoke-серверу (expected 2xx). Route: %s, Response: %s. Output:\n%s", route, response.code(), output)
                        .isTrue();
            }
        }
        Duration probeLatency = Duration.ofNanos(System.nanoTime() - probeStartNanos);

        boolean finished = process.waitFor(processTimeout.toSeconds(), TimeUnit.SECONDS);
        reader.join(5_000L);

        String fullOutput = output.toString();
        assertThat(finished)
                .as("Smoke JVM должна завершиться за %s. Output:\n%s", processTimeout, fullOutput)
                .isTrue();
        assertThat(process.exitValue())
                .as("Smoke JVM exit code. Output:\n%s", fullOutput)
                .isZero();
        if (requireAgentStartup) {
            assertThat(fullOutput)
                .as("OTel Java Agent должен стартовать без ошибок")
                    .doesNotContain("OpenTelemetry Javaagent failed to start");
        }
        return new RunResult(fullOutput, probeLatency);
    }

    /**
     * Собирает {@code -D...}-свойства child-JVM (agent + OTLP export + платформенные дефолты).
     * Package-private: переиспользуется {@link LongLivedAgentSmokeProcess} — единый набор
     * свойств для одно- и многофазных smoke-сценариев.
     */
    static List<String> buildJvmProperties(
            String extensionLocation,
            String serviceName,
            String otlpEndpoint,
            List<String> extraJvmSystemProperties) {
        List<String> jvmProperties = new ArrayList<>();
        if (extensionLocation != null && !extensionLocation.isBlank()) {
            // otel.javaagent.extensions поддерживает список путей через запятую.
            // Валидируем каждый путь отдельно и собираем обратно, нормализуя разделители для Windows.
            List<String> normalizedPaths = new ArrayList<>();
            for (String rawPath : extensionLocation.split(java.io.File.pathSeparator)) {
                String trimmed = rawPath.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Path extensionPath = Path.of(trimmed);
                assertThat(Files.exists(extensionPath))
                        .as("Extension JAR или каталог: %s", trimmed)
                        .isTrue();
                normalizedPaths.add(extensionPath.toString().replace('\\', '/'));
            }
            if (!normalizedPaths.isEmpty()) {
                // OTel Java Agent expects extensions to be separated by comma or File.pathSeparator? 
                // Let's check OpenTelemetry documentation: "Path to the extension JAR file or a directory... 
                // Wait, it says 'multiple paths separated by the platform's path separator'.
                jvmProperties.add("otel.javaagent.extensions=" + String.join(java.io.File.pathSeparator, normalizedPaths));
            }
        }
        ControlledAgentSpringFixture.addSdkAutoConfigurationExclusion(jvmProperties, extensionLocation);
        jvmProperties.add("otel.service.name=" + serviceName);
        String tracesEndpoint = JaegerTestContainerSupport.resolveOtlpHttpTracesEndpoint(otlpEndpoint);
        jvmProperties.add("otel.traces.exporter=otlp");
        jvmProperties.add("otel.exporter.otlp.endpoint=" + otlpEndpoint);
        jvmProperties.add("otel.exporter.otlp.traces.endpoint=" + tracesEndpoint);
        jvmProperties.add("otel.exporter.otlp.protocol=http/protobuf");
        // E2E smoke: короткий процесс; stock BSP надёжнее DropOldest при graceful shutdown.
        jvmProperties.add("platform.tracing.queue.overflow-policy=UPSTREAM");
        jvmProperties.add("otel.metrics.exporter=none");
        jvmProperties.add("otel.logs.exporter=none");
        jvmProperties.add("otel.javaagent.logging=application");
        jvmProperties.add("org.slf4j.simpleLogger.defaultLogLevel=info");
        if (extraJvmSystemProperties == null
                || extraJvmSystemProperties.stream().noneMatch(p -> p != null && p.startsWith("otel.bsp.schedule.delay"))) {
            jvmProperties.add("otel.bsp.schedule.delay=200");
        }
        if (extraJvmSystemProperties != null) {
            for (String property : extraJvmSystemProperties) {
                if (property != null && !property.isBlank()) {
                    jvmProperties.add(property);
                }
            }
        }
        return jvmProperties;
    }

    private static boolean waitForReady(Process process, StringBuilder output, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (ControlledAgentSpringFixture.containsOutputLine(output, READY_MARKER)) {
                return true;
            }
            if (!process.isAlive()) {
                return false;
            }
            Thread.sleep(100L);
        }
        return ControlledAgentSpringFixture.containsOutputLine(output, READY_MARKER);
    }
}
