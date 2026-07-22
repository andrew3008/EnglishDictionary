package space.br1440.platform.tracing.e2e.support;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import space.br1440.platform.tracing.e2e.smoke.AgentWebFluxReactorPropagationSmokeMain;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запуск дочерней JVM с OTel Java Agent и {@link AgentWebFluxReactorPropagationSmokeMain}.
 */
public final class AgentWebFluxProcessRunner {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(15))
            .build();

    private AgentWebFluxProcessRunner() {
    }

    /**
     * @return тело ответа {@code /propagation-test}
     */
    public static String runPropagationTest(
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
        return runConcurrentPropagationTest(
                otelAgentJar, testRuntimeClasspath, otlpEndpoint, serviceName, httpPort,
                extensionLocation, extraEnv, extraJvmSystemProperties, processTimeout, flushDelayMs, 1).get(0);
    }

    public static List<String> runConcurrentPropagationTest(
            String otelAgentJar,
            String testRuntimeClasspath,
            String otlpEndpoint,
            String serviceName,
            int httpPort,
            String extensionLocation,
            Map<String, String> extraEnv,
            List<String> extraJvmSystemProperties,
            Duration processTimeout,
            long flushDelayMs,
            int requestCount) throws Exception {
        assertThat(requestCount).isPositive();
        List<RequestSpec> requests = new ArrayList<>();
        for (int index = 0; index < requestCount; index++) {
            requests.add(new RequestSpec("/propagation-test", Map.of()));
        }
        return runConcurrentRequests(
                otelAgentJar, testRuntimeClasspath, otlpEndpoint, serviceName, httpPort,
                extensionLocation, extraEnv, extraJvmSystemProperties, processTimeout, flushDelayMs, requests);
    }

    public static List<String> runConcurrentIdentityTest(
            String otelAgentJar,
            String testRuntimeClasspath,
            String otlpEndpoint,
            String serviceName,
            int httpPort,
            String extensionLocation,
            Map<String, String> extraEnv,
            List<String> extraJvmSystemProperties,
            Duration processTimeout,
            long flushDelayMs,
            List<String> correlationIds) throws Exception {
        List<RequestSpec> requests = correlationIds.stream()
                .map(correlationId -> new RequestSpec(
                        "/identity-reactive?correlationId=" + correlationId,
                        Map.of(
                                "X-Request-Id", "request-" + correlationId,
                                "X-Correlation-ID", "spoofed-" + correlationId,
                                "baggage", "platform.correlation.id=spoofed-" + correlationId)))
                .toList();
        return runConcurrentRequests(
                otelAgentJar, testRuntimeClasspath, otlpEndpoint, serviceName, httpPort,
                extensionLocation, extraEnv, extraJvmSystemProperties, processTimeout, flushDelayMs, requests);
    }

    private static List<String> runConcurrentRequests(
            String otelAgentJar,
            String testRuntimeClasspath,
            String otlpEndpoint,
            String serviceName,
            int httpPort,
            String extensionLocation,
            Map<String, String> extraEnv,
            List<String> extraJvmSystemProperties,
            Duration processTimeout,
            long flushDelayMs,
            List<RequestSpec> requestSpecs) throws Exception {
        assertThat(requestSpecs).isNotEmpty();
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
        command.add(AgentWebFluxReactorPropagationSmokeMain.class.getName());
        command.add(Integer.toString(httpPort));
        command.add(Long.toString(flushDelayMs));
        command.add(Integer.toString(requestSpecs.size()));

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
        }, "webflux-agent-smoke-output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean ready = waitForReady(process, output, processTimeout);
        assertThat(ready)
                .as("Agent WebFlux JVM должна вывести READY за %s. Output:\n%s", processTimeout, output)
                .isTrue();

        List<CompletableFuture<String>> requests = new ArrayList<>();
        for (RequestSpec requestSpec : requestSpecs) {
            requests.add(CompletableFuture.supplyAsync(() -> executeRequest(httpPort, requestSpec, output)));
        }
        List<String> responseBodies = requests.stream().map(CompletableFuture::join).toList();

        boolean finished = process.waitFor(processTimeout.toSeconds(), TimeUnit.SECONDS);
        reader.join(5_000L);

        String fullOutput = output.toString();
        assertThat(finished)
                .as("Agent WebFlux JVM должна завершиться за %s. Output:\n%s", processTimeout, fullOutput)
                .isTrue();
        assertThat(process.exitValue())
                .as("Agent WebFlux JVM exit code. Output:\n%s", fullOutput)
                .isZero();
        assertThat(fullOutput)
                .as("OTel Java Agent должен стартовать без ошибок")
                .contains("WEBFLUX_E2:openTelemetryBeans=0")
                .doesNotContain("OpenTelemetry Javaagent failed to start");
        return responseBodies;
    }

    private static String executeRequest(int httpPort, RequestSpec requestSpec, StringBuilder output) {
        Request.Builder requestBuilder = new Request.Builder()
                .url("http://127.0.0.1:" + httpPort + requestSpec.path())
                .get();
        requestSpec.headers().forEach(requestBuilder::header);
        Request request = requestBuilder.build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            assertThat(response.isSuccessful())
                    .as("HTTP probe к %s. Output:\n%s", requestSpec.path(), output)
                    .isTrue();
            assertThat(response.body()).isNotNull();
            return response.body().string();
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось выполнить WebFlux E2E запрос", exception);
        }
    }

    private record RequestSpec(String path, Map<String, String> headers) {
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
            if (ControlledAgentSpringFixture.containsOutputLine(
                    output, AgentWebFluxReactorPropagationSmokeMain.READY_MARKER)) {
                return true;
            }
            if (!process.isAlive()) {
                return false;
            }
            Thread.sleep(100L);
        }
        return ControlledAgentSpringFixture.containsOutputLine(
                output, AgentWebFluxReactorPropagationSmokeMain.READY_MARKER);
    }
}
