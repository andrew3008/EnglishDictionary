package space.br1440.platform.tracing.autoconfigure.reactive.harness;

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
import java.util.zip.GZIPInputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;

final class HarnessOtlpTraceCollector implements AutoCloseable {

    private static final String TRACES_PATH = "/v1/traces";

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<ReceivedSpan> receivedSpans = new CopyOnWriteArrayList<>();

    private HarnessOtlpTraceCollector(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    static HarnessOtlpTraceCollector start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);

        HarnessOtlpTraceCollector collector = new HarnessOtlpTraceCollector(server, executor);
        server.createContext(TRACES_PATH, collector::handle);
        server.start();
        return collector;
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<ReceivedSpan> receivedSpans() {
        return List.copyOf(receivedSpans);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            receivedSpans.addAll(parse(readBody(exchange)));
            byte[] responseBody = ExportTraceServiceResponse.newBuilder().build().toByteArray();
            exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, responseBody.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(responseBody);
            }
        }
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        InputStream body = exchange.getRequestBody();
        if ("gzip".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
            body = new GZIPInputStream(body);
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        body.transferTo(buffer);
        return buffer.toByteArray();
    }

    private static List<ReceivedSpan> parse(byte[] body) throws IOException {
        return ExportTraceServiceRequest.parseFrom(body).getResourceSpansList().stream()
                .flatMap(resource -> resource.getScopeSpansList().stream())
                .flatMap(scope -> scope.getSpansList().stream())
                .map(HarnessOtlpTraceCollector::toReceivedSpan)
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
                parentSpanId,
                span.getKind().name(),
                span.getFlags(),
                Map.copyOf(attributes)
        );
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    record ReceivedSpan(String traceId,
                        String parentSpanId,
                        String kind,
                        int flags,
                        Map<String, String> stringAttributes) {
    }
}
