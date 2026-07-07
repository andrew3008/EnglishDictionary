package space.br1440.platform.tracing.otel.extension.jmx.metrics;

import java.util.Map;

@SuppressWarnings("unused")
public interface PlatformProcessorMetricsControlMBean {

    long getForcedSpanCloses();

    long getForcedTraceCloses();

    int getActiveSpanCount();

    int getActiveTraceCount();

    long getProcessorErrorsTotal();

    Map<String, Long> getProcessorErrorsByName();

    long getDroppedAttributesTotal();

    long getDroppedEventsTotal();

    long getDroppedLinksTotal();

}
