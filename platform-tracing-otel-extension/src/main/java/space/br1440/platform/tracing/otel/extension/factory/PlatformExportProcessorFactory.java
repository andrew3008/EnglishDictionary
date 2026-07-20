package space.br1440.platform.tracing.otel.extension.factory;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.otel.extension.configuration.enums.QueueOverflowPolicy;
import space.br1440.platform.tracing.otel.extension.configuration.QueueExtensionConfig;
import space.br1440.platform.tracing.otel.extension.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingJmxRegistrar;
import space.br1440.platform.tracing.otel.extension.processor.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.extension.readiness.PlatformExtensionCapability;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public final class PlatformExportProcessorFactory {

    private final AtomicReference<SafeSpanExporter> capturedExporter = new AtomicReference<>();
    private final AtomicInteger exporterCount = new AtomicInteger();
    private final AtomicBoolean dropOldestEnabledLogged = new AtomicBoolean();

    private final PlatformTracingJmxRegistrar jmxRegistrar;

    public PlatformExportProcessorFactory(PlatformTracingJmxRegistrar jmxRegistrar) {
        this.jmxRegistrar = jmxRegistrar;
    }

    public SpanExporter captureExporter(SpanExporter exporter) {
        int exporterRegistrationIndex = exporterCount.incrementAndGet();

        SafeSpanExporter safeExporter;
        if (exporter instanceof SafeSpanExporter safe) {
            safeExporter = safe;
        } else {
            safeExporter = new SafeSpanExporter(exporter);
        }

        if (exporterRegistrationIndex == 1) {
            capturedExporter.set(safeExporter);

            if (jmxRegistrar != null) {
                jmxRegistrar.setSafeExporter(safeExporter);
                jmxRegistrar.extensionReadiness().markInstalled(
                        PlatformExtensionCapability.SAFE_EXPORTER_INSTALLED);
            }
        }

        return safeExporter;
    }

    public SpanProcessor maybeReplaceExportProcessor(SpanProcessor processor,
                                                     QueueExtensionConfig queueConfig,
                                                     ConfigProperties config) {
        if (isExplicitUpstream(queueConfig)) {
            markExportPathProtected();
            return processor;
        }

        if (exporterCount.get() > 1) {
            log.warn("""
                    Platform DROP_OLDEST: more than one SpanExporter detected (count={}).
                    Custom processor supports only a single effective exporter.
                    Falling back to stock BatchSpanProcessor (UPSTREAM).
                    For fan-out scenarios use OTel Collector.""",
                    exporterCount.get());
            markExportPathProtected();
            return processor;
        }

        SpanExporter exporter = capturedExporter.get();
        if (exporter == null) {
            log.warn("""
                    Platform DROP_OLDEST: captured SpanExporter is null.
                    Falling back — processor left unchanged.""");
            return processor;
        }

        if (!(processor instanceof BatchSpanProcessor)) {
            log.warn("""
                    Platform DROP_OLDEST: pipeline processor is {} (not a BatchSpanProcessor).
                    Falling back — passthrough.""",
                    processor.getClass().getName());
            markExportPathProtected();
            return processor;
        }

        try {
            processor.shutdown().join(5, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.warn("""
                    Platform DROP_OLDEST: stock BatchSpanProcessor.shutdown() failed: {}.
                    Proceeding with replacement.""",
                    e.getMessage());
        }

        PlatformDropOldestExportSpanProcessor replacement = PlatformDropOldestExportSpanProcessor
                .builder(exporter)
                .readBspConfigFrom(config)
                .build();

        if (jmxRegistrar != null) {
            jmxRegistrar.setExportProcessor(replacement);
        }
        markExportPathProtected();

        if (dropOldestEnabledLogged.compareAndSet(false, true)) {
            log.info("""
                    Platform DROP_OLDEST export processor enabled. \
                    queueCapacity={}, exporter={}""",
                    replacement.getQueueCapacity(), exporter.getClass().getName());
        }

        return replacement;
    }

    private void markExportPathProtected() {
        if (jmxRegistrar != null && capturedExporter.get() != null) {
            jmxRegistrar.extensionReadiness().markInstalled(
                    PlatformExtensionCapability.EXPORT_PATH_PROTECTED);
        }
    }

    private static boolean isExplicitUpstream(QueueExtensionConfig queueConfig) {
        String normalized = queueConfig.overflowPolicy().trim().toUpperCase(Locale.ROOT);
        if (QueueOverflowPolicy.UPSTREAM.value().equals(normalized)) {
            return true;
        }

        if (QueueOverflowPolicy.DROP_OLDEST.value().equals(normalized)) {
            return false;
        }

        log.warn("""
                Unknown value for platform.tracing.queue.overflow-policy='{}'. \
                Expected '{}' or '{}'; treating as '{}' (DROP_OLDEST).""",
                queueConfig.overflowPolicy(),
                QueueOverflowPolicy.UPSTREAM.value(),
                QueueOverflowPolicy.DROP_OLDEST.value(),
                QueueOverflowPolicy.DROP_OLDEST.value());

        return false;
    }
}
