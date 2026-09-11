package space.br1440.platform.tracing.otel.javaagent.distribution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class OtlpPartialSuccessCollector implements AutoCloseable {

    private static final String TRACES_PATH = "/v1/traces";
    private static final long REJECTED_SPANS_PER_BATCH = 1L;

    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicInteger receivedBatches = new AtomicInteger();

    private OtlpPartialSuccessCollector(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    static OtlpPartialSuccessCollector start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);

        OtlpPartialSuccessCollector collector = new OtlpPartialSuccessCollector(server, executor);
        server.createContext(TRACES_PATH, collector::handle);
        server.start();
        return collector;
    }

    String tracesEndpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + TRACES_PATH;
    }

    int receivedBatches() {
        return receivedBatches.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            byte[] requestBody = readBody(exchange);
            long spanCount = countSpans(requestBody);
            receivedBatches.incrementAndGet();

            byte[] responseBody = ExportTraceServiceResponse.newBuilder()
                    .setPartialSuccess(ExportTracePartialSuccess.newBuilder()
                            .setRejectedSpans(Math.min(REJECTED_SPANS_PER_BATCH, spanCount))
                            .setErrorMessage("stub-collector: synthetic partial rejection")
                            .build())
                    .build()
                    .toByteArray();

            exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, responseBody.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(responseBody);
            }
        } finally {
            exchange.close();
        }
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

    private static long countSpans(byte[] requestBody) throws IOException {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(requestBody);
        return request.getResourceSpansList().stream()
                .flatMap(resourceSpans -> resourceSpans.getScopeSpansList().stream())
                .mapToLong(scopeSpans -> scopeSpans.getSpansCount())
                .sum();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
