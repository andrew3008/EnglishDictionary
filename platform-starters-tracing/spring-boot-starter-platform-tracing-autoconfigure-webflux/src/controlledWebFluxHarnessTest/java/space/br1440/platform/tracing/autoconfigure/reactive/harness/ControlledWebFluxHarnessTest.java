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

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;

class ControlledWebFluxHarnessTest {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);
    private static final String SAMPLED_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String UNSAMPLED_TRACE_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SERVER_TRACE_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String SERVER_PARENT_SPAN_ID = "3333333333333333";
    private static final String SERVER_REQUEST_ID = "req-server-span";
    private static final String CORRELATION_TRACE_ID = "dddddddddddddddddddddddddddddddd";
    private static final String CORRELATION_PARENT_SPAN_ID = "4444444444444444";
    private static final String ABSENT_TRACE_ID = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String ABSENT_PARENT_SPAN_ID = "5555555555555555";
    private static final String CORRELATION_ID = "trusted-correlation";
    private static final int REMOTE_PARENT_FLAG = 0x200;

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
        try (HarnessOtlpTraceCollector collector = HarnessOtlpTraceCollector.start()) {
        Files.writeString(arguments, String.join(System.lineSeparator(), launchArguments(
                agent,
                sha256(agent),
                classpath,
                collector.endpoint()
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
            HttpResponse<String> serverRequest = exchange(
                    client,
                    port,
                    "/hop",
                    traceparent(SERVER_TRACE_ID, SERVER_PARENT_SPAN_ID, "01"),
                    SERVER_REQUEST_ID
            );
            assertThat(serverRequest.headers().firstValue(PlatformHeaders.X_REQUEST_ID)).contains(SERVER_REQUEST_ID);
            HarnessOtlpTraceCollector.ReceivedSpan server = awaitServerSpan(collector, SERVER_TRACE_ID);
            assertThat(server.parentSpanId()).isEqualTo(SERVER_PARENT_SPAN_ID);
            assertThat(server.flags() & REMOTE_PARENT_FLAG).isNotZero();
            assertThat(server.stringAttributes())
                    .containsEntry(PlatformAttributes.PLATFORM_REQUEST_ID, SERVER_REQUEST_ID);
            exchange(
                    client,
                    port,
                    "/hop",
                    traceparent(CORRELATION_TRACE_ID, CORRELATION_PARENT_SPAN_ID, "01"),
                    null,
                    CORRELATION_ID
            );
            HarnessOtlpTraceCollector.ReceivedSpan correlated = awaitServerSpan(collector, CORRELATION_TRACE_ID);
            assertThat(correlated.stringAttributes())
                    .containsEntry(PlatformAttributes.PLATFORM_CORRELATION_ID, CORRELATION_ID);
            exchange(
                    client,
                    port,
                    "/hop",
                    traceparent(ABSENT_TRACE_ID, ABSENT_PARENT_SPAN_ID, "01"),
                    null,
                    null
            );
            HarnessOtlpTraceCollector.ReceivedSpan absent = awaitServerSpan(collector, ABSENT_TRACE_ID);
            assertThat(absent.stringAttributes()).doesNotContainKey(PlatformAttributes.PLATFORM_CORRELATION_ID);
            assertThat(collector.receivedSpans())
                    .filteredOn(span -> ABSENT_TRACE_ID.equals(span.traceId()))
                    .allSatisfy(span -> assertThat(span.stringAttributes())
                            .doesNotContainKey(PlatformAttributes.PLATFORM_CORRELATION_ID));
        } finally {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
        }
    }

    private static List<String> launchArguments(Path agent, String sha256, String classpath, String otlpEndpoint) {
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
        arguments.add("-Dotel.exporter.otlp.traces.protocol=http/protobuf");
        arguments.add("-Dotel.exporter.otlp.endpoint=" + otlpEndpoint);
        arguments.add("-Dotel.exporter.otlp.traces.compression=none");
        arguments.add("-Dotel.bsp.schedule.delay=200");
        arguments.add("-Dotel.metrics.exporter=none");
        arguments.add("-Dotel.logs.exporter=none");
        arguments.add("-Dplatform.tracing.enabled=true");
        arguments.add("-Dplatform.tracing.propagation.correlation.accept-inbound=true");
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
        return exchange(client, port, path, traceparent, null, null).body();
    }

    private static HttpResponse<String> exchange(HttpClient client,
                                                 int port,
                                                 String path,
                                                 String traceparent,
                                                 String requestId) throws Exception {
        return exchange(client, port, path, traceparent, requestId, null);
    }

    private static HttpResponse<String> exchange(HttpClient client,
                                                 int port,
                                                 String path,
                                                 String traceparent,
                                                 String requestId,
                                                 String correlationId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        if (requestId != null) {
            request.header(PlatformHeaders.X_REQUEST_ID, requestId);
        }
        if (correlationId != null) {
            request.header(PlatformHeaders.X_CORRELATION_ID, correlationId);
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return response;
    }

    private static HarnessOtlpTraceCollector.ReceivedSpan awaitServerSpan(HarnessOtlpTraceCollector collector,
                                                                          String traceId) {
        try {
            List<HarnessOtlpTraceCollector.ReceivedSpan> spans = await("export of WebFlux SERVER span " + traceId)
                    .pollInSameThread()
                    .pollInterval(Duration.ofMillis(200))
                    .during(Duration.ofMillis(500))
                    .atMost(Duration.ofSeconds(20))
                    .until(() -> collector.receivedSpans().stream()
                                    .filter(span -> traceId.equals(span.traceId()))
                                    .filter(span -> "SPAN_KIND_SERVER".equals(span.kind()))
                                    .toList(),
                            found -> found.size() == 1);
            return spans.getFirst();
        } catch (ConditionTimeoutException timeout) {
            throw new IllegalStateException(
                    "Expected one SERVER span for " + traceId + ", received " + collector.receivedSpans(),
                    timeout
            );
        }
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
