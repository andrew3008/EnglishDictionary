package space.br1440.platform.tracing.otel.javaagent.jmx.metrics;

import javax.management.openmbean.CompositeData;

public interface PlatformProcessorMetricsControlMBean {

    CompositeData getProcessorMetricsObservation();

}
