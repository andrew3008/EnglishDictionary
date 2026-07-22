package space.br1440.platform.tracing.autoconfigure.errorhandling;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.CorrelationScope;
import space.br1440.platform.tracing.api.context.RequestTraceContextSnapshot;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;

class RequestTraceContextSnapshotSupplierTest {

    private OpenTelemetrySdk sdk;
    private TracingRuntime runtime;
    private DefaultTraceOperations traceOperations;
    private RequestTraceContextSnapshotSupplier supplier;

    @BeforeEach
    void setUp() {
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .build();
        runtime = OtelTracingRuntimeFactory.create(sdk);
        traceOperations = new DefaultTraceOperations(runtime);
        supplier = new RequestTraceContextSnapshotSupplier(() -> traceOperations);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        sdk.close();
    }

    @Test
    void returnsAllFourFieldsFromSingleContextView() {
        Tracer tracer = sdk.getTracer("test");
        Span span = tracer.spanBuilder("test-op").startSpan();
        try (Scope ignored = span.makeCurrent();
             CorrelationScope request = runtime.openRequestIdentityScope("request-42");
             CorrelationScope correlation = traceOperations.openCorrelationScope("business-42")) {

            RequestTraceContextSnapshot snapshot = supplier.get();

            assertThat(snapshot.requestId()).isEqualTo("request-42");
            assertThat(snapshot.correlationId()).isEqualTo("business-42");
            assertThat(snapshot.traceId()).isEqualTo(span.getSpanContext().getTraceId());
            assertThat(snapshot.spanId()).isEqualTo(span.getSpanContext().getSpanId());
        } finally {
            span.end();
        }
    }

    @Test
    void mdcIsProjectionOnlyAndCannotChangeSnapshot() {
        MDC.put("requestId", "forged-request");
        MDC.put("correlationId", "forged-correlation");

        RequestTraceContextSnapshot snapshot = supplier.get();

        assertThat(snapshot.requestId()).isNull();
        assertThat(snapshot.correlationId()).isNull();
        assertThat(snapshot.traceId()).isNull();
        assertThat(snapshot.spanId()).isNull();
    }

    @Test
    void missingFacadeReturnsEmptySnapshot() {
        RequestTraceContextSnapshot snapshot = new RequestTraceContextSnapshotSupplier(() -> null).get();

        assertThat(snapshot.requestId()).isNull();
        assertThat(snapshot.correlationId()).isNull();
        assertThat(snapshot.traceId()).isNull();
        assertThat(snapshot.spanId()).isNull();
    }

    @Test
    void supplierFailureDoesNotEscape() {
        RequestTraceContextSnapshot snapshot = new RequestTraceContextSnapshotSupplier(() -> {
            throw new IllegalStateException("broken context");
        }).get();

        assertThat(snapshot).isEqualTo(new RequestTraceContextSnapshot(null, null, null, null));
    }
}
