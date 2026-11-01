package space.br1440.platform.tracing.otel.javaagent.exporter.guard;

import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

import io.opentelemetry.exporter.internal.otlp.traces.LowAllocationTraceRequestMarshaler;
import io.opentelemetry.sdk.trace.data.SpanData;

final class OtlpSpanSizeMeasurer implements ToIntFunction<SpanData> {

    private final LowAllocationTraceRequestMarshaler marshaler;

    OtlpSpanSizeMeasurer() {
        this(new LowAllocationTraceRequestMarshaler());
    }

    OtlpSpanSizeMeasurer(LowAllocationTraceRequestMarshaler marshaler) {
        this.marshaler = Objects.requireNonNull(marshaler, "marshaler");
    }

    @Override
    public int applyAsInt(SpanData span) {
        try {
            marshaler.initialize(List.of(span));
            return marshaler.getBinarySerializedSize();
        } finally {
            marshaler.reset();
        }
    }
}
