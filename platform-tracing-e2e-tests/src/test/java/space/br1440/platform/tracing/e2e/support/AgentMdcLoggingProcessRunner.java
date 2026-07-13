package space.br1440.platform.tracing.e2e.support;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import space.br1440.platform.tracing.e2e.smoke.AgentMdcPlatformLoggingSmokeMain;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запуск дочерней JVM с OTel Java Agent и {@link AgentMdcPlatformLoggingSmokeMain}.
 */
public final class AgentMdcLoggingProcessRunner {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(15))
            .build();

    private AgentMdcLoggingProcessRunner() {
    }

    /**
     * @return полный stdout/stderr subprocess (включая log lines)
     */
    public static ProcessResult runMdcTest(
            String otelAgentJar,
            String testRuntimeClasspath,
            String otlpEndpoint,
            String serviceName,
            int httpPort,
            String extensionLocation,
            Map<String, String> extraEnv,
            List<String> extraJvmSystemProperties,
            Duration processTimeout,
            long flushDelayMs) throws Exception {
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
        command.add(AgentMdcPlatformLoggingSmokeMain.class.getName());
        command.add(Integer.toString(httpPort));
        command.add(Long.toString(flushDelayMs));

        ProcessBuilder builder = new ProcessBuilder(command);
        if (extraEnv != null) {
            builder.environment().putAll(extraEnv);
        }
        builder.redirectErrorStream(true);

        Process process = builder.start();

        StringBuilder output = new StringBuilder();
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
        }, "mdc-agent-smoke-output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean ready = waitForReady(process, output, processTimeout);
        assertThat(ready)
                .as("Agent MDC JVM должна вывести READY за %s. Output:\n%s", processTimeout, output)
                .isTrue();

        String responseBody;
        Request request = new Request.Builder()
                .url("http://127.0.0.1:" + httpPort + "/mdc-test")
                .get()
                .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            assertThat(response.isSuccessful())
                    .as("HTTP probe к /mdc-test. Output:\n%s", output)
                    .isTrue();
            assertThat(response.body()).isNotNull();
            responseBody = response.body().string();
        }

        boolean finished = process.waitFor(processTimeout.toSeconds(), TimeUnit.SECONDS);
        reader.join(5_000L);

        String fullOutput = output.toString();
        assertThat(finished)
                .as("Agent MDC JVM должна завершиться за %s. Output:\n%s", processTimeout, fullOutput)
                .isTrue();
        assertThat(process.exitValue())
                .as("Agent MDC JVM exit code. Output:\n%s", fullOutput)
                .isZero();
        assertThat(fullOutput)
                .as("OTel Java Agent должен стартовать без ошибок")
                .doesNotContain("OpenTelemetry Javaagent failed to start");
        return new ProcessResult(fullOutput, responseBody);
    }

    public record ProcessResult(String processOutput, String httpTraceId) {
    }

    private static List<String> buildJvmProperties(
            String extensionLocation,
            String serviceName,
            String otlpEndpoint,
            List<String> extraJvmSystemProperties) {
        List<String> jvmProperties = new ArrayList<>();
        if (extensionLocation != null && !extensionLocation.isBlank()) {
            Path extensionPath = Path.of(extensionLocation);
            assertThat(Files.exists(extensionPath))
                    .as("Extension JAR или каталог: %s", extensionLocation)
                    .isTrue();
            jvmProperties.add("otel.javaagent.extensions=" + extensionPath.toString().replace('\\', '/'));
        }
        jvmProperties.add("otel.service.name=" + serviceName);
        jvmProperties.add("otel.traces.exporter=otlp");
        jvmProperties.add("otel.exporter.otlp.endpoint=" + otlpEndpoint);
        jvmProperties.add("otel.exporter.otlp.traces.endpoint="
                + JaegerTestContainerSupport.resolveOtlpHttpTracesEndpoint(otlpEndpoint));
        jvmProperties.add("otel.exporter.otlp.protocol=http/protobuf");
        jvmProperties.add("platform.tracing.queue.overflow-policy=UPSTREAM");
        jvmProperties.add("otel.metrics.exporter=none");
        jvmProperties.add("otel.logs.exporter=none");
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
            if (output.indexOf(AgentMdcPlatformLoggingSmokeMain.READY_MARKER) >= 0) {
                return true;
            }
            if (!process.isAlive()) {
                return false;
            }
            Thread.sleep(100L);
        }
        return output.indexOf(AgentMdcPlatformLoggingSmokeMain.READY_MARKER) >= 0;
    }
}
