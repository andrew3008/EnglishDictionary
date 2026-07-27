package space.br1440.platform.tracing.e2e.configserver.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class ConfigServerResponseStub implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<Response> response = new AtomicReference<>();

    public ConfigServerResponseStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "config-server-response-stub");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    public String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void respond(Map<String, Object> properties, String version, String state) {
        response.set(new Response(new LinkedHashMap<>(properties), version, state));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Response current = response.get();
        if (current == null) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        byte[] body = current.toJson().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private record Response(Map<String, Object> properties, String version, String state) {

        private String toJson() {
            StringBuilder json = new StringBuilder(256);
            json.append("{\"name\":\"provenance-client\",\"profiles\":[\"default\"],")
                    .append("\"label\":\"main\",");
            appendNullable(json, "version", version);
            json.append(',');
            appendNullable(json, "state", state);
            json.append(",\"propertySources\":[{\"name\":\"stub:application.yml\",\"source\":{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(escape(entry.getKey())).append("\":");
                Object value = entry.getValue();
                if (value instanceof Number || value instanceof Boolean) {
                    json.append(value);
                } else {
                    json.append('"').append(escape(String.valueOf(value))).append('"');
                }
            }
            return json.append("}}]}").toString();
        }

        private static void appendNullable(StringBuilder json, String name, String value) {
            json.append('"').append(name).append("\":");
            if (value == null) {
                json.append("null");
            } else {
                json.append('"').append(escape(value)).append('"');
            }
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
