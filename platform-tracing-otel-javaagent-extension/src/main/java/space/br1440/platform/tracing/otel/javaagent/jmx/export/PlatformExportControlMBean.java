package space.br1440.platform.tracing.otel.javaagent.jmx.export;

import java.util.Map;

/**
 * P6.6: platform Export MBean is read-only. Mutations go through private {@code applySnapshot}.
 */
@SuppressWarnings("unused")
public interface PlatformExportControlMBean {

    boolean isExportEnabled();

    long getExportDroppedOverflowTotal();

    long getExportDroppedAfterShutdownTotal();

    long getExportFailuresTotal();

    long getExportTimeoutsTotal();

    int getExportQueueCapacity();

    int getExportQueueSize();

    Map<String, Long> getSafeExporterMetrics();
}
