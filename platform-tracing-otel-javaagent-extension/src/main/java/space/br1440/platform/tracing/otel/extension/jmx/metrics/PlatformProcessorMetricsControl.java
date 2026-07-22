package space.br1440.platform.tracing.otel.extension.jmx.metrics;

import lombok.RequiredArgsConstructor;
import space.br1440.platform.tracing.otel.extension.processor.MetricsSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.SpanWatchdogProcessor;

import java.util.Collections;
import java.util.Map;

@RequiredArgsConstructor
public final class PlatformProcessorMetricsControl implements PlatformProcessorMetricsControlMBean {

    private final SpanWatchdogProcessor watchdog;
    private final PlatformCompositeSpanProcessor composite;
    private final MetricsSpanProcessor metrics;

    @Override
    public long getForcedSpanCloses() {
        return (watchdog == null) ? 0L : watchdog.getForcedSpanCloses();
    }

    @Override
    public long getForcedTraceCloses() {
        return (watchdog == null) ? 0L : watchdog.getForcedTraceCloses();
    }

    @Override
    public int getActiveSpanCount() {
        return (watchdog == null) ? 0 : watchdog.getActiveSpanCount();
    }

    @Override
    public int getActiveTraceCount() {
        return (watchdog == null) ? 0 : watchdog.getActiveTraceCount();
    }

    @Override
    public long getProcessorErrorsTotal() {
        if (composite == null) {
            return 0L;
        }

        long sum = 0L;
        for (Long value : composite.getProcessorErrorCounts().values()) {
            if (value != null) {
                sum += value;
            }
        }

        return sum;
    }

    @Override
    public Map<String, Long> getProcessorErrorsByName() {
        if (composite == null) {
            return Collections.emptyMap();
        }

        return composite.getProcessorErrorCounts();
    }

    @Override
    public long getDroppedAttributesTotal() {
        return (metrics == null) ? 0L : metrics.getDroppedAttributes();
    }

    @Override
    public long getDroppedEventsTotal() {
        return (metrics == null) ? 0L : metrics.getDroppedEvents();
    }

    @Override
    public long getDroppedLinksTotal() {
        return (metrics == null) ? 0L : metrics.getDroppedLinks();
    }
}
