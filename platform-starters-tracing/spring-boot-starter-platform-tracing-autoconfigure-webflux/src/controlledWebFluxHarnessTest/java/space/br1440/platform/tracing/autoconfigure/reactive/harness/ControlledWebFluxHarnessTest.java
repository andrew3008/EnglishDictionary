package space.br1440.platform.tracing.autoconfigure.reactive.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

class ControlledWebFluxHarnessTest {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);
    private static final String SAMPLED_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String UNSAMPLED_TRACE_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void controlledAgentPreservesContextAcrossHopsAndCancellation() throws Exception {
        // given
        Path agent = Path.of(requiredProperty("platform.tracing.controlledAgent"));
        String classpath = requiredProperty("platform.tracing.webfluxHarness.classpath");
        assertThat(agent).isRegularFile();
        Path evidence = Path.of("build", "controlled-webflux-harness");
        Files.createDirectories(evidence);
        Path stdout = evidence.resolve("harness.stdout.log");
        Path stderr = evidence.resolve("harness.stderr.log");
        Path arguments = evidence.resolve("harness.jvm.args");
        Files.writeString(arguments, String.join(System.lineSeparator(), launchArguments(
                agent,
                sha256(agent),
                classpath
        )), StandardCharsets.UTF_8);

        // when
        Process process = start(arguments, stdout, stderr);
        try {
            String ready = awaitReady(stdout, process, stderr);
            int port = Integer.parseInt(ready.substring(ready.lastIndexOf('=') + 1).trim());
            HttpClient client = HttpClient.newHttpClient();
            String sampled = body(client, port, "/hop", traceparent(SAMPLED_TRACE_ID, "00f067aa0ba902b7", "01"));
            String unsampled = body(client, port, "/hop", traceparent(UNSAMPLED_TRACE_ID, "bbbbbbbbbbbbbbbb", "00"));
            String wrapped = body(client, port, "/wrap", null);
            String cancelled = body(client, port, "/cancel", traceparent(SAMPLED_TRACE_ID, "1111111111111111", "01"));
            String next = body(client, port, "/hop", traceparent("cccccccccccccccccccccccccccccccc", "2222222222222222", "01"));
            String startup = Files.readString(stdout, StandardCharsets.UTF_8);
            String sdkReport = startup.lines()
                    .filter(line -> line.startsWith("R1_SDK "))
                    .findFirst()
                    .orElse("");

            // then
            assertThat(sdkReport).startsWith("R1_SDK applicationSdkVisible=false ");
            assertThat(sdkReport).doesNotContain("io.opentelemetry.sdk.OpenTelemetrySdk");
            assertThat(field(sampled, 0)).isEqualTo(SAMPLED_TRACE_ID);
            assertThat(field(sampled, 1)).isEqualTo("true");
            assertThat(field(sampled, 2)).isEqualTo("true");
            assertThat(field(sampled, 3)).isEqualTo(SAMPLED_TRACE_ID);
            assertThat(field(sampled, 4)).isEqualTo("true");
            assertThat(field(sampled, 5)).isEqualTo("true");
            assertThat(field(sampled, 6)).startsWith("r1-worker");
            assertThat(field(unsampled, 0)).isEqualTo(UNSAMPLED_TRACE_ID);
            assertThat(field(unsampled, 1)).isEqualTo("true");
            assertThat(field(unsampled, 2)).isEqualTo("false");
            assertThat(field(unsampled, 3)).isEqualTo(UNSAMPLED_TRACE_ID);
            assertThat(field(unsampled, 4)).isEqualTo("true");
            assertThat(field(unsampled, 5)).isEqualTo("false");
            assertThat(field(unsampled, 6)).startsWith("r1-worker");
            assertThat(wrapped).startsWith(ReactorScopeProbeController.WRAPPED_TRACE_ID + "|");
            assertThat(field(wrapped, 1)).isEqualTo("true");
            assertThat(field(wrapped, 2)).isEqualTo("false");
            assertThat(field(wrapped, 3)).isEqualTo(ReactorScopeProbeController.WRAPPED_TRACE_ID);
            assertThat(field(wrapped, 4)).isEqualTo("outer");
            assertThat(field(wrapped, 5)).startsWith("r1-wrap");
            assertThat(field(cancelled, 0)).isEqualTo(SAMPLED_TRACE_ID);
            assertThat(field(cancelled, 1)).isEqualTo(field(cancelled, 0));
            assertThat(next).as("next hop").startsWith(
                    "cccccccccccccccccccccccccccccccc|true|true|cccccccccccccccccccccccccccccccc|");
            assertThat(field(next, 3)).isNotEqualTo(field(cancelled, 0));
        } finally {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static List<String> launchArguments(Path agent, String sha256, String classpath) {
        List<String> arguments = new ArrayList<>();
        arguments.add("-Dfile.encoding=UTF-8");
        arguments.add("-Dstdout.encoding=UTF-8");
        arguments.add("-Dstderr.encoding=UTF-8");
        arguments.add("-Duser.timezone=UTC");
        arguments.add("-Dotel.javaagent.logging=space.br1440.platform");
        arguments.add("-Dspring.main.banner-mode=off");
        arguments.add("-Dspring.main.web-application-type=reactive");
        arguments.add("-Dserver.port=0");
        arguments.add("-Dlogging.level.root=ERROR");
        arguments.add("-Dotel.traces.exporter=otlp");
        arguments.add("-Dplatform.tracing.exporter.enabled=false");
        arguments.add("-Dotel.metrics.exporter=none");
        arguments.add("-Dotel.logs.exporter=none");
        arguments.add("-Dplatform.tracing.enabled=true");
        arguments.add("-Dplatform.tracing.control.channel=NONE");
        arguments.add("-Dplatform.tracing.service.name=controlled-webflux-harness");
        arguments.add("-Dplatform.tracing.service.version=1.0.0");
        arguments.add("-Dplatform.tracing.service.environment=test");
        arguments.add("-Dplatform.tracing.resource.validation-mode=ERROR");
        arguments.add("-Dplatform.tracing.controlled-agent.expected-sha256=" + sha256);
        arguments.add("-Dreactor.netty.ioWorkerCount=1");
        arguments.add("-javaagent:" + agent);
        arguments.add("-cp");
        arguments.add(classpath);
        arguments.add(ControlledWebFluxHarness.class.getName());
        return arguments;
    }

    private static Process start(Path arguments, Path stdout, Path stderr) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        ProcessBuilder processBuilder = new ProcessBuilder(java, "@" + arguments.toAbsolutePath());
        processBuilder.redirectOutput(stdout.toFile());
        processBuilder.redirectError(stderr.toFile());
        processBuilder.environment().keySet().removeIf(name -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            return normalized.equals("JAVA_TOOL_OPTIONS")
                    || normalized.equals("_JAVA_OPTIONS")
                    || normalized.equals("JDK_JAVA_OPTIONS")
                    || normalized.equals("CLASSPATH")
                    || normalized.startsWith("OTEL_")
                    || normalized.startsWith("PLATFORM_TRACING_")
                    || normalized.startsWith("SPRING_");
        });
        return processBuilder.start();
    }

    private static String awaitReady(Path stdout, Process process, Path stderr) throws Exception {
        try {
            return await("Harness readiness")
                    .pollInSameThread()
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(200))
                    .atMost(STARTUP_TIMEOUT)
                    .until(() -> {
                        if (!process.isAlive()) {
                            throw new IllegalStateException(failure("Harness exited before ready", stdout, stderr));
                        }

                        return Files.readString(stdout, StandardCharsets.UTF_8).lines()
                                .filter(line -> line.startsWith("R1_READY port="))
                                .findFirst()
                                .orElse(null);
                    }, Objects::nonNull);
        } catch (ConditionTimeoutException timeout) {
            throw new IllegalStateException(failure("Harness did not become ready", stdout, stderr), timeout);
        }
    }

    private static String body(HttpClient client, int port, String path, String traceparent) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return response.body();
    }

    private static String traceparent(String traceId, String spanId, String flags) {
        return "00-" + traceId + "-" + spanId + "-" + flags;
    }

    private static String field(String payload, int index) {
        String[] fields = payload.split("\\|", -1);
        assertThat(fields.length).as(payload).isGreaterThan(index);
        return fields[index];
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertThat(value).as(name).isNotBlank();
        return value;
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String failure(String message, Path stdout, Path stderr) throws IOException {
        return message + System.lineSeparator()
                + "stdout:" + System.lineSeparator() + tail(stdout)
                + System.lineSeparator() + "stderr:" + System.lineSeparator() + tail(stderr);
    }

    private static String tail(Path file) throws IOException {
        if (Files.notExists(file)) {
            return "<missing>";
        }

        String text = Files.readString(file, StandardCharsets.UTF_8);
        return text.length() <= 4_000 ? text : text.substring(text.length() - 4_000);
    }
}
