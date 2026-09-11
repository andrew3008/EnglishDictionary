package space.br1440.platform.tracing.otel.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;

class NoOpTracingRuntimeTraceparentContractTest {

    private final OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder().build();

    @Test
    void traceparent_isEmpty_evenWithForeignActiveSpan() {
        // given
        TraceOperations disabled = NoopTraceOperations.forDisabledRuntime(
                NoOpTracingRuntime.disabledByConfiguration("test")
        );
        Span foreignSpan = openTelemetry.getTracer("foreign-instrumentation").spanBuilder("foreign").startSpan();

        // when
        try (var ignored = foreignSpan.makeCurrent()) {
            // then
            assertThat(foreignSpan.getSpanContext().isValid()).isTrue();
            assertThat(disabled.traceContext().traceparent()).isEmpty();
        } finally {
            foreignSpan.end();
        }
    }
}
