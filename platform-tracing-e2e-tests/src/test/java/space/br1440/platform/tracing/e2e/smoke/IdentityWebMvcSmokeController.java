package space.br1440.platform.tracing.e2e.smoke;

import java.util.concurrent.CountDownLatch;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import space.br1440.platform.tracing.api.CorrelationScope;
import space.br1440.platform.tracing.api.TraceOperations;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;

@RestController
class IdentityWebMvcSmokeController {

    private final TraceOperations traceOperations;
    private final CountDownLatch servedLatch;

    IdentityWebMvcSmokeController(TraceOperations traceOperations, CountDownLatch servedLatch) {
        this.traceOperations = traceOperations;
        this.servedLatch = servedLatch;
    }

    @GetMapping("/identity")
    String identity() {
        String requestId = traceOperations.traceContext().requestId().orElseThrow();
        boolean spoofRejected = traceOperations.traceContext().correlationId().isEmpty()
                && Baggage.current().getEntryValue("platform.correlation.id") == null;
        System.out.println("IDENTITY_WEBMVC:requestId=" + requestId);
        System.out.println("IDENTITY_WEBMVC:spoofRejected=" + spoofRejected);

        try (CorrelationScope ignored = traceOperations.openCorrelationScope("local-webmvc-correlation")) {
            System.out.println("IDENTITY_WEBMVC:localCorrelation="
                    + traceOperations.traceContext().correlationId().orElse("missing"));
            Span child = GlobalOpenTelemetry.getTracer("slice-m-e2e")
                    .spanBuilder("identity-local-child")
                    .startSpan();
            child.end();
        } finally {
            System.out.println("IDENTITY_WEBMVC:correlationAfterScope="
                    + traceOperations.traceContext().correlationId().orElse("empty"));
            servedLatch.countDown();
            System.out.flush();
        }
        return "ok";
    }
}
