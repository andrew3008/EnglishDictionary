package space.br1440.platform.tracing.otel.runtime.otel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;

class OtelTracingRuntimeTraceparentFormatTest {

    private static final String TRACE_ID = "0102030405060708090a0b0c0d0e0f10";
    private static final String SPAN_ID = "0102030405060708";

    private final TraceOperations tracing = new DefaultTraceOperations(
            OtelTracingRuntimeFactory.create(
                    OpenTelemetrySdk.builder().build()
            )
    );

    @Test
    void sampledContext_returnsTraceparentWithSampledFlag() {
        // given
        SpanContext spanContext = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());

        // when
        try (Scope ignored = Context.root().with(Span.wrap(spanContext)).makeCurrent()) {
            // then
            assertThat(tracing.traceContext().traceparent())
                    .hasValue("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
            assertMatchesW3CPropagator();
        }
    }

    @Test
    void unsampledContext_returnsTraceparentWithUnsampledFlag() {
        // given
        SpanContext spanContext = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getDefault(), TraceState.getDefault());

        // when
        try (Scope ignored = Context.root().with(Span.wrap(spanContext)).makeCurrent()) {
            // then
            assertThat(tracing.traceContext().traceparent())
                    .hasValue("00-" + TRACE_ID + "-" + SPAN_ID + "-00");
            assertMatchesW3CPropagator();
        }
    }

    @Test
    void validNonRecordingContext_stillReturnsTraceparent() {
        // given
        SpanContext spanContext = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
        Span nonRecordingSpan = Span.wrap(spanContext);

        // when
        try (Scope ignored = Context.root().with(nonRecordingSpan).makeCurrent()) {
            // then
            assertThat(nonRecordingSpan.isRecording()).isFalse();
            assertThat(tracing.traceContext().traceparent()).isPresent();
        }
    }

    @Test
    void noActiveContext_isEmpty() {
        // given / when / then
        assertThat(tracing.traceContext().traceparent()).isEmpty();
    }

    @Test
    void repeatedCalls_doNotMutateActiveContext() {
        // given
        SpanContext spanContext = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());

        // when
        try (Scope ignored = Context.root().with(Span.wrap(spanContext)).makeCurrent()) {
            Optional<String> first = tracing.traceContext().traceparent();
            Optional<String> second = tracing.traceContext().traceparent();

            // then
            assertThat(first).isEqualTo(second);
            assertThat(Span.current().getSpanContext().getSpanId()).isEqualTo(SPAN_ID);
        }
    }

    private void assertMatchesW3CPropagator() {
        // given
        Map<String, String> carrier = new HashMap<>();

        // when
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, (c, key, value) -> {
            if (c != null) {
                c.put(key, value);
            }
        });

        // then
        assertThat(tracing.traceContext().traceparent()).hasValue(carrier.get("traceparent"));
    }
}
