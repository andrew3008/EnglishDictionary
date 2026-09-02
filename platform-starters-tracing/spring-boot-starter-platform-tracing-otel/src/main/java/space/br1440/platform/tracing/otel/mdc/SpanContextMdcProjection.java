package space.br1440.platform.tracing.otel.mdc;

import org.slf4j.MDC;

import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class SpanContextMdcProjection {

    public static Snapshot installCurrent() {
        Snapshot previous = Snapshot.capture();
        project(Span.current().getSpanContext());
        return previous;
    }

    private static void project(SpanContext spanContext) {
        if (spanContext.isValid()) {
            putOrRemove(TracingMdcKeys.TRACE_ID, spanContext.getTraceId());
            putOrRemove(TracingMdcKeys.SPAN_ID, spanContext.getSpanId());
            putOrRemove(TracingMdcKeys.TRACE_FLAGS, spanContext.getTraceFlags().asHex());
            return;
        }

        MDC.remove(TracingMdcKeys.TRACE_ID);
        MDC.remove(TracingMdcKeys.SPAN_ID);
        MDC.remove(TracingMdcKeys.TRACE_FLAGS);
    }

    private static void putOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    public record Snapshot(String traceId, String spanId, String traceFlags) {

        private static Snapshot capture() {
            return new Snapshot(
                    MDC.get(TracingMdcKeys.TRACE_ID),
                    MDC.get(TracingMdcKeys.SPAN_ID),
                    MDC.get(TracingMdcKeys.TRACE_FLAGS)
            );
        }

        public void restore() {
            putOrRemove(TracingMdcKeys.TRACE_ID, traceId);
            putOrRemove(TracingMdcKeys.SPAN_ID, spanId);
            putOrRemove(TracingMdcKeys.TRACE_FLAGS, traceFlags);
        }
    }
}
