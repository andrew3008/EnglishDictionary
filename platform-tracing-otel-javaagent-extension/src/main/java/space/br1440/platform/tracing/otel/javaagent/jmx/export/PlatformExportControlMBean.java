package space.br1440.platform.tracing.otel.javaagent.jmx.export;

import java.util.Map;

@SuppressWarnings("unused")
public interface PlatformExportControlMBean {

    boolean isExportEnabled();

    void setExportEnabled(boolean enabled);

    long getExportDroppedOverflowTotal();

    long getExportDroppedAfterShutdownTotal();

    long getExportFailuresTotal();

    long getExportTimeoutsTotal();

    int getExportQueueCapacity();

    int getExportQueueSize();

    Map<String, Long> getSafeExporterMetrics();

}
