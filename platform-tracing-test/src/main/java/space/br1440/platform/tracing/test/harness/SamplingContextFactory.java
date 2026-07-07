package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import space.br1440.platform.tracing.api.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.api.propagation.control.PlatformTraceControl;

import java.util.Locale;

/**
 * Фабрика {@link Context} для characterization-тестов sampling rule chain.
 */
public final class SamplingContextFactory {

    private static final String DEFAULT_TRACE_ID = "00000000000000000000000000000001";
    private static final String DEFAULT_SPAN_ID = "0000000000000002";

    private SamplingContextFactory() {
    }

    public static Context root() {
        return Context.root();
    }

    public static Context withForceHeader(String rawForceValue) {
        boolean force = rawForceValue != null
                && "on".equalsIgnoreCase(rawForceValue.trim());
        PlatformTraceControl control = new PlatformTraceControl(
                force,
                false,
                null,
                force ? "x_trace_on" : null,
                rawForceValue);
        return Context.root().with(PlatformTraceContextKeys.TRACE_CONTROL, control);
    }

    public static Context withQaTrace() {
        PlatformTraceControl control = new PlatformTraceControl(
                false,
                true,
                null,
                "qa_trace",
                null);
        return Context.root().with(PlatformTraceContextKeys.TRACE_CONTROL, control);
    }

    public static Context withForceAndQa(String rawForceValue) {
        boolean force = rawForceValue != null
                && "on".equalsIgnoreCase(rawForceValue.trim());
        PlatformTraceControl control = new PlatformTraceControl(
                force,
                true,
                null,
                force ? "x_trace_on" : "qa_trace",
                rawForceValue);
        return Context.root().with(PlatformTraceContextKeys.TRACE_CONTROL, control);
    }

    public static Context withSampledParent() {
        return withParentTraceFlags(TraceFlags.getSampled());
    }

    public static Context withNotSampledParent() {
        return withParentTraceFlags(TraceFlags.getDefault());
    }

    private static Context withParentTraceFlags(TraceFlags flags) {
        SpanContext parent = SpanContext.create(
                DEFAULT_TRACE_ID,
                DEFAULT_SPAN_ID,
                flags,
                TraceState.getDefault());
        return Context.root().with(Span.wrap(parent));
    }

    public static Context withNotSampledParentAndForceHeader(String rawForceValue) {
        return withForceHeader(rawForceValue).with(
                Span.wrap(SpanContext.create(
                        DEFAULT_TRACE_ID,
                        DEFAULT_SPAN_ID,
                        TraceFlags.getDefault(),
                        TraceState.getDefault())));
    }

    public static Context withNotSampledParentAndQaTrace() {
        return withQaTrace().with(
                Span.wrap(SpanContext.create(
                        DEFAULT_TRACE_ID,
                        DEFAULT_SPAN_ID,
                        TraceFlags.getDefault(),
                        TraceState.getDefault())));
    }

    /** Нормализует force-значение так же, как {@code ForceHeaderRule}. */
    public static String normalizedForceValue(String raw) {
        return raw == null ? null : raw.toLowerCase(Locale.ROOT);
    }
}
