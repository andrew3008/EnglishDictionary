package space.br1440.platform.tracing.otel.javaagent.exporter;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.StatusData;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ExporterTestSpans {

    public static final SpanContext LINK_TARGET = SpanContext.create(
            "00000000000000000000000000000abc", "000000000000abc1",
            TraceFlags.getDefault(), TraceState.getDefault());

    public static TestSpanData.Builder baseSpan(String name) {
        return TestSpanData.builder()
                .setName(name)
                .setKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .setStatus(StatusData.unset())
                .setStartEpochNanos(0L)
                .setEndEpochNanos(1L)
                .setHasEnded(true);
    }
}
