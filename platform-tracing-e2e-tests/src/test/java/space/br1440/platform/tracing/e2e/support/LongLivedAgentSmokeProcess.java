package space.br1440.platform.tracing.e2e.support;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Долгоживущий child-процесс со Spring Boot smoke-приложением под OTel Java Agent —
 * для многофазных e2e-сценариев (runtime-смена конфигурации между сериями запросов).
 * <p>
 * Отличие от {@link AgentHttpSpringSmokeProcessRunner} (один probe-запрос → exit):
 * процесс живёт, пока тест не вызовет управляющий endpoint (например
 * {@code POST /admin/shutdown}), что позволяет проверять семантику runtime-операций
 * (PR-C, ADR-runtime-sampling-policy). Свойства child-JVM — общие
 * ({@link AgentHttpSpringSmokeProcessRunner#buildJvmProperties}).
 *
 * <p>Контракт smoke-main тот же: args {@code <port> <flushDelayMs>}, маркер READY в stdout.</p>
 */
public final class LongLivedAgentSmokeProcess implements AutoCloseable {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(15))
            .build();

    private final Process process;
    private final StringBuilder output;
    private final Thread outputReader;
    private final int httpPort;

    private LongLivedAgentSmokeProcess(Process process, StringBuilder output, Thread outputReader, int httpPort) {
        this.process = process;
        this.output = output;
        this.outputReader = outputReader;
        this.httpPort = httpPort;
    }

    /** Запускает child-JVM и блокируется до маркера READY (иначе {@link IllegalStateException}). */
    public static LongLivedAgentSmokeProcess start(
            String mainClassName,
            String otelAgentJar,
            String testRuntimeClasspath,
            String otlpEndpoint,
            String serviceName,
            int httpPort,
            String extensionLocation,
            List<String> extraJvmSystemProperties,
            Duration readyTimeout,
            long flushDelayMs) throws Exception {

        List<String> jvmProperties = AgentHttpSpringSmokeProcessRunner.buildJvmProperties(
                extensionLocation, serviceName, otlpEndpoint, extraJvmSystemProperties);

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
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
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append(System.lineSeparator());
                    }
                }
            } catch (Exception ignored) {
                // процесс завершился — поток закрыт
            }
        }, "long-lived-smoke-output-reader");
        reader.setDaemon(true);
        reader.start();

        long deadline = System.nanoTime() + readyTimeout.toNanos();
        boolean ready = false;
        while (System.nanoTime() < deadline) {
            synchronized (output) {
                if (ControlledAgentSpringFixture.containsOutputLine(
                        output, AgentHttpSpringSmokeProcessRunner.READY_MARKER)) {
                    ready = true;
                    break;
                }
            }
            if (!process.isAlive()) {
                break;
            }
            Thread.sleep(100L);
        }
        if (!ready) {
            process.destroyForcibly();
            throw new IllegalStateException("Smoke JVM не вывела READY за " + readyTimeout
                    + ". Output:\n" + output);
        }
        return new LongLivedAgentSmokeProcess(process, output, reader, httpPort);
    }

    /** GET к smoke-приложению; возвращает HTTP-код (тело игнорируется). */
    public int httpGet(String route, Map<String, String> headers) throws Exception {
        Request.Builder request = new Request.Builder()
                .url("http://127.0.0.1:" + httpPort + route)
                .get();
        if (headers != null) {
            headers.forEach(request::header);
        }
        try (Response response = HTTP_CLIENT.newCall(request.build()).execute()) {
            return response.code();
        }
    }

    /** POST без тела; возвращает тело ответа (для проверки версии конфигурации). */
    public PostResult httpPost(String routeWithQuery) throws Exception {
        Request request = new Request.Builder()
                .url("http://127.0.0.1:" + httpPort + routeWithQuery)
                .post(RequestBody.create(new byte[0]))
                .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            return new PostResult(response.code(), body);
        }
    }

    public record PostResult(int code, String body) {
    }

    /**
     * Ожидает завершения процесса (после {@code POST /admin/shutdown}); возвращает exit code.
     * По таймауту убивает процесс и бросает {@link IllegalStateException}.
     */
    public int awaitExit(Duration timeout) throws InterruptedException {
        boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        outputReader.join(5_000L);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Smoke JVM не завершилась за " + timeout
                    + ". Output:\n" + output);
        }
        return process.exitValue();
    }

    /** Полный stdout/stderr процесса (для диагностики в assert-сообщениях). */
    public String output() {
        synchronized (output) {
            return output.toString();
        }
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
