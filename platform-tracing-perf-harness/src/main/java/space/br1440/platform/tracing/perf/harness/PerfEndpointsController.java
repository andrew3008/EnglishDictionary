package space.br1440.platform.tracing.perf.harness;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;

import java.util.Map;

@RestController
@RequestMapping("/perf")
public class PerfEndpointsController {

    private static final int WORK_ITERATIONS = 2_000;

    private final ObjectProvider<TraceOperations> tracingProvider;

    public PerfEndpointsController(ObjectProvider<TraceOperations> tracingProvider) {
        this.tracingProvider = tracingProvider;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "harness", "platform-tracing-perf-harness");
    }

    @GetMapping("/fast")
    public Map<String, Object> fast() {
        long checksum = PerfWork.deterministicChecksum(1L, 256);
        TraceOperations tracing = tracingProvider.getIfAvailable();
        if (tracing == null) {
            return Map.of("path", "fast", "checksum", checksum);
        }
        try (SpanHandle scope = tracing.spans().operation("perf-fast").start()) {
            Span.current().setAttribute("platform.perf.path", "fast");
            Span.current().setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.SUCCESS.value());
            Span.current().setStatus(StatusCode.OK);
            return Map.of("path", "fast", "checksum", checksum);
        }
    }

    @GetMapping("/work")
    public Map<String, Object> work() {
        long checksum = PerfWork.deterministicChecksum(42L, WORK_ITERATIONS);
        TraceOperations tracing = tracingProvider.getIfAvailable();
        if (tracing == null) {
            return Map.of("path", "work", "checksum", checksum);
        }
        try (SpanHandle scope = tracing.spans().operation("perf-work").start()) {
            Span.current().setAttribute("platform.perf.path", "work");
            Span.current().setAttribute("platform.perf.iterations", (long) WORK_ITERATIONS);
            Span.current().setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.SUCCESS.value());
            Span.current().setStatus(StatusCode.OK);
            return Map.of("path", "work", "checksum", checksum);
        }
    }

    @GetMapping("/validation/valid")
    public Map<String, Object> validationValid() {
        long checksum = PerfWork.deterministicChecksum(100L, WORK_ITERATIONS);
        TraceOperations tracing = tracingProvider.getIfAvailable();
        if (tracing == null) {
            return Map.of("path", "validation-valid", "checksum", checksum);
        }
        try (SpanHandle scope = tracing.spans().operation("perf-validation-valid").start()) {
            Span.current().setAttribute("platform.perf.path", "validation-valid");
            Span.current().setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.SUCCESS.value());
            Span.current().setStatus(StatusCode.OK);
            return Map.of("path", "validation-valid", "checksum", checksum);
        }
    }

    @GetMapping("/validation/missing")
    public Map<String, Object> validationMissing() {
        long checksum = PerfWork.deterministicChecksum(200L, WORK_ITERATIONS);
        TraceOperations tracing = tracingProvider.getIfAvailable();
        if (tracing == null) {
            return Map.of("path", "validation-missing", "checksum", checksum);
        }
        try (SpanHandle scope = tracing.spans().operation("perf-validation-missing").start()) {
            Span.current().setAttribute("platform.perf.path", "validation-missing");
            return Map.of("path", "validation-missing", "checksum", checksum);
        }
    }
}
