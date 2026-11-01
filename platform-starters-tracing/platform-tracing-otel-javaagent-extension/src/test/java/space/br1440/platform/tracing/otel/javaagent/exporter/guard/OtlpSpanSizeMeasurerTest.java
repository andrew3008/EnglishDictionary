package space.br1440.platform.tracing.otel.javaagent.exporter.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.exporter.ExporterTestSpans;

import io.opentelemetry.exporter.internal.otlp.traces.LowAllocationTraceRequestMarshaler;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;

class OtlpSpanSizeMeasurerTest {

    @Test
    void releasesSdkStateAfterSuccessfulAndFailedMeasurement() {
        // given
        LowAllocationTraceRequestMarshaler marshaler = spy(new LowAllocationTraceRequestMarshaler());
        OtlpSpanSizeMeasurer measurer = new OtlpSpanSizeMeasurer(marshaler);
        SpanData healthy = ExporterTestSpans.baseSpan("healthy").build();
        int expectedSize = measurer.applyAsInt(healthy);
        verify(marshaler).reset();
        clearInvocations(marshaler);

        IllegalStateException failure = new IllegalStateException("Simulated span read failure");
        SpanData broken = new DelegatingSpanData(ExporterTestSpans.baseSpan("x".repeat(1000)).build()) {
            @Override
            public long getStartEpochNanos() {
                throw failure;
            }
        };

        // when/then
        assertThatThrownBy(() -> measurer.applyAsInt(broken)).isSameAs(failure);
        verify(marshaler).reset();
        assertThat(measurer.applyAsInt(healthy)).isEqualTo(expectedSize).isPositive();
    }
}
