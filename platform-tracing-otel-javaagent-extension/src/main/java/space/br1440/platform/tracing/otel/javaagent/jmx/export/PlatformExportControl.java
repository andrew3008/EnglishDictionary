package space.br1440.platform.tracing.otel.javaagent.jmx.export;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.processor.PlatformDropOldestExportSpanProcessor;

@SuppressWarnings("resource")
@RequiredArgsConstructor
public final class PlatformExportControl implements PlatformExportControlMBean {

    private final Supplier<PlatformDropOldestExportSpanProcessor> exportProcessorSupplier;
    private final Supplier<SafeSpanExporter> safeExporterSupplier;

    private PlatformDropOldestExportSpanProcessor exportProcessorOrNull() {
        return (exportProcessorSupplier == null) ? null : exportProcessorSupplier.get();
    }

    private SafeSpanExporter safeExporterOrNull() {
        return (safeExporterSupplier == null) ? null : safeExporterSupplier.get();
    }

    @Override
    public boolean isExportEnabled() {
        SafeSpanExporter exporter = safeExporterOrNull();
        return (exporter != null) && exporter.isExportEnabled();
    }

    @Override
    public long getExportDroppedOverflowTotal() {
        PlatformDropOldestExportSpanProcessor proc = exportProcessorOrNull();
        return (proc == null) ? 0L : proc.getDroppedSpansOverflow();
    }

    @Override
    public long getExportDroppedAfterShutdownTotal() {
        PlatformDropOldestExportSpanProcessor proc = exportProcessorOrNull();
        return (proc == null) ? 0L : proc.getDroppedSpansAfterShutdown();
    }

    @Override
    public long getExportFailuresTotal() {
        PlatformDropOldestExportSpanProcessor proc = exportProcessorOrNull();
        return (proc == null) ? 0L : proc.getExportFailures();
    }

    @Override
    public long getExportTimeoutsTotal() {
        PlatformDropOldestExportSpanProcessor proc = exportProcessorOrNull();
        return (proc == null) ? 0L : proc.getExportTimeouts();
    }

    @Override
    public int getExportQueueCapacity() {
        PlatformDropOldestExportSpanProcessor proc = exportProcessorOrNull();
        return (proc == null) ? 0 : proc.getQueueCapacity();
    }

    @Override
    public int getExportQueueSize() {
        PlatformDropOldestExportSpanProcessor proc = exportProcessorOrNull();
        return (proc == null) ? 0 : proc.getQueueSize();
    }

    @Override
    public Map<String, Long> getSafeExporterMetrics() {
        SafeSpanExporter exporter = safeExporterOrNull();
        return (exporter == null) ? Collections.emptyMap() : exporter.metricsSnapshot();
    }
}
