package space.br1440.platform.tracing.perftests.sut;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class OrdersController {

    private final ObjectProvider<TraceOperations> tracingProvider;

    public OrdersController(ObjectProvider<TraceOperations> tracingProvider) {
        this.tracingProvider = tracingProvider;
    }

    @GetMapping("/api/orders/{id}")
    public Map<String, Object> getOrder(@PathVariable("id") long id) {
        TraceOperations tracing = tracingProvider.getIfAvailable();
        if (tracing == null) {
            return buildOrder(id);
        }
        try (SpanHandle scope = tracing.spans().operation("load-order").start()) {
            populateProdLikeAttributes(id);
            Map<String, Object> order = buildOrder(id);
            Span.current().setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.SUCCESS.value());
            Span.current().setStatus(StatusCode.OK);
            return order;
        }
    }

    @PostMapping("/api/orders")
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> request) {
        long id = ThreadLocalRandom.current().nextLong(1, 1_000_000);
        TraceOperations tracing = tracingProvider.getIfAvailable();
        if (tracing == null) {
            return buildOrder(id);
        }
        try (SpanHandle scope = tracing.spans().operation("create-order").start()) {
            populateProdLikeAttributes(id);
            Span.current().setAttribute("platform.perf.request.size", (long) request.size());
            Map<String, Object> order = buildOrder(id);
            Span.current().setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.SUCCESS.value());
            Span.current().setStatus(StatusCode.OK);
            return order;
        }
    }

    @GetMapping("/api/error")
    public ResponseEntity<Void> error() {
        throw new IllegalStateException("perf-scenario synthetic failure: order backend unavailable");
    }

    @GetMapping("/api/slow")
    public Map<String, Object> slow(@RequestParam(name = "ms", defaultValue = "200") long ms)
            throws InterruptedException {
        Thread.sleep(Math.min(ms, 5_000));
        return buildOrder(1);
    }

    private static Map<String, Object> buildOrder(long id) {
        long checksum = 0;
        for (int i = 0; i < 2_000; i++) {
            checksum = checksum * 31 + (id ^ i);
        }
        return Map.of(
                "orderId", id,
                "status", "CONFIRMED",
                "items", 3,
                "totalCents", 129_900,
                "warehouse", "msk-01",
                "checksum", checksum);
    }

    private static void populateProdLikeAttributes(long id) {
        Span span = Span.current();
        span.setAttribute("platform.perf.order.id", id);
        span.setAttribute("platform.perf.warehouse", "msk-01");
        span.setAttribute("enduser.id", "user-" + (id % 1000));
        span.setAttribute("http.request.header.authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.perf.sig");
        span.setAttribute("db.password", "s3cr3t-value");
    }
}
