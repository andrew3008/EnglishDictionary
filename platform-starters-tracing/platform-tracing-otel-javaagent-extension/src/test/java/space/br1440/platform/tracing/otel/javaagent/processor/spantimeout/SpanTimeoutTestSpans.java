package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

import lombok.experimental.UtilityClass;

@UtilityClass
final class SpanTimeoutTestSpans {

    static ReadWriteSpan span(int traceOrdinal, int spanOrdinal) {
        return span(hex(traceOrdinal, 32), hex(spanOrdinal, 16));
    }

    static ReadWriteSpan span(String traceId, String spanId) {
        ReadWriteSpan span = mock(ReadWriteSpan.class);
        SpanContext context = SpanContext.create(
                traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()
        );
        when(span.getSpanContext()).thenReturn(context);
        when(span.hasEnded()).thenReturn(false);
        return span;
    }

    static String hex(int value, int width) {
        String raw = Integer.toHexString(value);
        return "0".repeat(width - raw.length()) + raw;
    }
}
