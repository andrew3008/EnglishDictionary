package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;

import com.sun.net.httpserver.HttpServer;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class BaggageBridgeProbe {

    public static final String OK_PREFIX = "BAGGAGE_BRIDGE_OK";
    public static final String PATH = "/bridge-probe";
    public static final String CORRELATION_ID = "workflow-42";
    public static final String BAGGAGE_HEADER_MARKER = "baggage=";

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final String LIFECYCLE_READY = "READY";
    private static final String LIFECYCLE_FAILED = "FAILED";

    public static void main(String[] arguments) throws Exception {
        waitForReady(ManagementFactory.getPlatformMBeanServer());
        String header = callLoopback();
        System.out.printf(Locale.ROOT, "%s %s%s%n", OK_PREFIX, BAGGAGE_HEADER_MARKER, header == null ? "" : header);
    }

    private static String callLoopback() throws IOException {
        AtomicReference<String> baggageHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, exchange -> {
            baggageHeader.set(exchange.getRequestHeaders().getFirst("baggage"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            Baggage baggage = Baggage.builder()
                    .put("platform.correlation.id", CORRELATION_ID)
                    .put("platform.other", "x")
                    .put("traffic_source", "probe")
                    .build();
            URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH);
            try (Scope ignored = baggage.storeInContext(Context.current()).makeCurrent()) {
                HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
                try {
                    connection.setRequestMethod("GET");
                    int status = connection.getResponseCode();
                    if (status != 200) {
                        throw new IllegalStateException("Loopback call failed with status " + status);
                    }
                    try (InputStream body = connection.getInputStream()) {
                        body.readAllBytes();
                    }
                } finally {
                    connection.disconnect();
                }
            }
        } finally {
            server.stop(0);
        }

        return baggageHeader.get();
    }

    private static void waitForReady(MBeanServer server) throws Exception {
        MonotonicDeadline deadline = MonotonicDeadline.after(READY_TIMEOUT);
        while (deadline.hasRemaining()) {
            if (server.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME)) {
                CompositeData snapshot = (CompositeData) server.getAttribute(
                        JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                        JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT
                );

                if (!JmxOpenTypes.READINESS_SNAPSHOT.equals(snapshot.getCompositeType())) {
                    throw new IllegalStateException(
                            "ReadinessSnapshot OpenType differs from the generated application contract");
                }

                String lifecycle = (String) snapshot.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE);
                if (LIFECYCLE_READY.equals(lifecycle)) {
                    return;
                }

                if (LIFECYCLE_FAILED.equals(lifecycle)) {
                    throw new IllegalStateException("Platform extension readiness failed before baggage bridge");
                }
            }

            Thread.sleep(POLL_INTERVAL.toMillis());
        }

        throw new IllegalStateException("Platform extension did not become READY before baggage bridge");
    }
}
