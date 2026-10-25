package space.br1440.platform.tracing.otel.javaagent.processor;

import java.util.concurrent.atomic.LongAdder;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Учитывает атрибуты/события/связи span'а, отброшенные SDK при превышении
 * {@code SpanLimits} (см. {@code SdkTracerProviderBuilder.setSpanLimits}), и экспонирует
 * суммарные счётчики для JMX/диагностики.
 */
public final class SpanLimitsMetricsProcessor implements SpanProcessor, ProcessorDiagnosticIdentity {

    private static final String DIAGNOSTIC_ID = JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_SPAN_LIMITS_METRICS;

    private final LongAdder droppedAttributes = new LongAdder();
    private final LongAdder droppedEvents = new LongAdder();
    private final LongAdder droppedLinks = new LongAdder();

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(@Nonnull ReadableSpan span) {
        SpanData spanData = span.toSpanData();
        recordDropped(droppedAttributes, spanData.getTotalAttributeCount(), spanData.getAttributes().size());
        recordDropped(droppedEvents, spanData.getTotalRecordedEvents(), spanData.getEvents().size());
        recordDropped(droppedLinks, spanData.getTotalRecordedLinks(), spanData.getLinks().size());
    }

    private static void recordDropped(LongAdder counter, long total, long actual) {
        long dropped = Math.max(0L, (total - actual));
        if (dropped > 0) {
            counter.add(dropped);
        }
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public String diagnosticId() {
        return DIAGNOSTIC_ID;
    }

    public long getDroppedAttributes() {
        return droppedAttributes.sum();
    }

    public long getDroppedEvents() {
        return droppedEvents.sum();
    }

    public long getDroppedLinks() {
        return droppedLinks.sum();
    }
}
