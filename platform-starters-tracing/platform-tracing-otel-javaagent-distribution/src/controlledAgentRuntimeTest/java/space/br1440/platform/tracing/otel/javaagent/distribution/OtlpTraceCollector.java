package space.br1440.platform.tracing.otel.javaagent.distribution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;

final class OtlpTraceCollector implements AutoCloseable {

    private static final String TRACES_PATH = "/v1/traces";
    private static final int DRAIN_TIMEOUT_SECONDS = 5;

    private final HttpServer server;
    private final ExecutorService executor;
    private final long rejectedSpansPerBatch;
    private final List<List<ReceivedSpan>> receivedSpanBatches = new CopyOnWriteArrayList<>();
    private final List<ReceivedSpan> receivedSpans = new CopyOnWriteArrayList<>();

    private OtlpTraceCollector(HttpServer server, ExecutorService executor, long rejectedSpansPerBatch) {
        this.server = server;
        this.executor = executor;
        this.rejectedSpansPerBatch = rejectedSpansPerBatch;
    }

    static OtlpTraceCollector startRejectingOneSpanPerBatch() throws IOException {
        return start(1L);
    }

    static OtlpTraceCollector startAcceptingAllSpans() throws IOException {
        return start(0L);
    }

    private static OtlpTraceCollector start(long rejectedSpansPerBatch) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);

        OtlpTraceCollector collector = new OtlpTraceCollector(server, executor, rejectedSpansPerBatch);
        server.createContext(TRACES_PATH, collector::handle);
        server.start();
        return collector;
    }

    String otlpEndpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<List<ReceivedSpan>> receivedSpanBatches() {
        return List.copyOf(receivedSpanBatches);
    }

    List<ReceivedSpan> receivedSpans() {
        return List.copyOf(receivedSpans);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] requestBody = readBody(exchange);
            List<ReceivedSpan> spans = parseSpans(requestBody);
            receivedSpanBatches.add(spans);
            receivedSpans.addAll(spans);

            byte[] responseBody = response(spans.size());
            exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, responseBody.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(responseBody);
            }
        }
    }

    private byte[] response(int spanCount) {
        ExportTraceServiceResponse.Builder response = ExportTraceServiceResponse.newBuilder();
        if (rejectedSpansPerBatch > 0L) {
            response.setPartialSuccess(ExportTracePartialSuccess.newBuilder()
                    .setRejectedSpans(Math.min(rejectedSpansPerBatch, spanCount))
                    .setErrorMessage("stub-collector: synthetic partial rejection")
                    .build());
        }

        return response.build().toByteArray();
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        InputStream body = exchange.getRequestBody();
        String contentEncoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
        if ("gzip".equalsIgnoreCase(contentEncoding)) {
            body = new GZIPInputStream(body);
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        body.transferTo(buffer);
        return buffer.toByteArray();
    }

    private static List<ReceivedSpan> parseSpans(byte[] requestBody) throws IOException {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(requestBody);
        return request.getResourceSpansList().stream()
                .flatMap(resourceSpans -> resourceSpans.getScopeSpansList().stream())
                .flatMap(scopeSpans -> scopeSpans.getSpansList().stream())
                .map(OtlpTraceCollector::toReceivedSpan)
                .toList();
    }

    private static ReceivedSpan toReceivedSpan(Span span) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (KeyValue attribute : span.getAttributesList()) {
            AnyValue value = attribute.getValue();
            if (value.hasStringValue()) {
                attributes.put(attribute.getKey(), value.getStringValue());
            }
        }

        HexFormat hex = HexFormat.of();
        String parentSpanId = span.getParentSpanId().isEmpty() ? "" : hex.formatHex(span.getParentSpanId().toByteArray());
        return new ReceivedSpan(
                hex.formatHex(span.getTraceId().toByteArray()),
                hex.formatHex(span.getSpanId().toByteArray()),
                span.getName(),
                Map.copyOf(attributes),
                parentSpanId
        );
    }

    @Override
    public void close() {
        server.stop(DRAIN_TIMEOUT_SECONDS);
        executor.shutdown();

        try {
            if (executor.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return;
            }

            int droppedExchanges = executor.shutdownNow().size();
            boolean terminatedAfterInterrupt = executor.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String forcedShutdownOutcome = terminatedAfterInterrupt ? "succeeded" : "FAILED, executor thread is still alive";
            String message = "OTLP trace collector did not drain within %d s; forced shutdown %s; %d queued exchange(s) dropped"
                    .formatted(
                            DRAIN_TIMEOUT_SECONDS,
                            forcedShutdownOutcome,
                            droppedExchanges
                    );
            throw new IllegalStateException(message);
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();

            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while draining OTLP trace collector", interrupted);
        }
    }

    record ReceivedSpan(String traceId,
                        String spanId,
                        String name,
                        Map<String, String> stringAttributes,
                        String parentSpanId) {
    }
}
