package space.br1440.platform.tracing.e2e;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.context.ConfigurableApplicationContext;

import com.sun.net.httpserver.HttpServer;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;

import io.opentelemetry.api.trace.Span;

final class PackagedSamplingScenario {

    static final String SUCCESS_TOKEN = "PACKAGED_SAMPLING_OK";
    private static final String UNSAMPLED_PARENT = "00-00000000000000000000000000000011-0000000000000012-00";

    private PackagedSamplingScenario() {
    }

    static void verify(ConfigurableApplicationContext context) throws IOException {
        TracingSettingsWriter writer = context.getBean(TracingSettingsWriter.class);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Span span = Span.current();
            String caseId = exchange.getRequestHeaders().getFirst("X-Sampling-Case");
            span.setAttribute("sampling.case", caseId);
            String flags = span.isRecording() + ":" + span.getSpanContext().isSampled();
            byte[] body = flags.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            request(port, "/drop", "sampling-hard-drop", Map.of("X-Trace-On", "on", "X-QA-Trace", "true"), false);
            request(port, "/other", "sampling-force", Map.of("X-Trace-On", "on", "X-QA-Trace", "true"), true);
            request(port, "/other", "sampling-qa", Map.of("X-QA-Trace", "true"), true);
            request(port, "/api/keep/item", "sampling-route-keep", Map.of(), true);
            request(port, "/api/noisy", "sampling-route-drop", Map.of(), false);
            request(port, "/other", "sampling-default-drop", Map.of(), false);
            request(port, "/api/keep/item", "sampling-parent-drop", Map.of("traceparent", UNSAMPLED_PARENT), false);
            request(port, "/other", "sampling-parent-force", Map.of("traceparent", UNSAMPLED_PARENT, "X-Trace-On", "on"), true);

            AcceptedSettings before = current(writer);
            apply(writer, before, before.policy().withSamplingRatio(1.0));
            request(port, "/other", "sampling-updated-default", Map.of(), true);
            request(port, "/api/noisy", "sampling-updated-route-drop", Map.of(), false);

            AcceptedSettings enabled = current(writer);
            if (enabled.revision().acceptedSequence() != before.revision().acceptedSequence() + 1) {
                throw new IllegalStateException("Sampling update did not advance accepted sequence exactly once");
            }
            apply(writer, enabled, enabled.policy().withSamplingEnabled(false));
            request(port, "/other", "sampling-kill-switch", Map.of("X-Trace-On", "on"), false);
            System.out.println(SUCCESS_TOKEN);
        } finally {
            server.stop(0);
        }
    }

    private static AcceptedSettings current(TracingSettingsWriter writer) {
        return writer.current().orElseThrow(() -> new IllegalStateException("Accepted sampling settings unavailable"));
    }

    private static void apply(TracingSettingsWriter writer, AcceptedSettings before, CompleteTracingSettings candidate) {
        if (writer.applySnapshot(before.revision(), candidate, SettingsWriter.ACTUATOR).outcome()
                != TracingSettingsDeliveryResult.APPLIED) {
            throw new IllegalStateException("Packaged sampling update was not applied");
        }
    }

    private static void request(int port, String path, String caseId, Map<String, String> headers,
                                boolean expectedSampled) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
            request.append("X-Sampling-Case: ").append(caseId).append("\r\n");
            headers.forEach((key, value) -> request.append(key).append(": ").append(value).append("\r\n"));
            request.append("\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String expected = expectedSampled + ":" + expectedSampled;
            if (!response.startsWith("HTTP/1.1 200") || !response.endsWith(expected)) {
                throw new IllegalStateException("Unexpected sampling flags for " + caseId + ": " + response);
            }
        }
    }
}
