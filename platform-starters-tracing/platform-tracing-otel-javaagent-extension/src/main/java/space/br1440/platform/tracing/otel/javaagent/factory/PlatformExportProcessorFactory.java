package space.br1440.platform.tracing.otel.javaagent.factory;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.otel.javaagent.configuration.QueueExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.DomainMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class PlatformExportProcessorFactory {

    private final AtomicReference<SafeSpanExporter> capturedExporter = new AtomicReference<>();
    private final AtomicReference<ReplacementAwareSpanExporter> capturedStockExporter = new AtomicReference<>();
    private final AtomicInteger exporterCount = new AtomicInteger();
    private final AtomicBoolean dropOldestEnabledLogged = new AtomicBoolean();
    private final Set<SafeSpanExporter> ownedExporters = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>())
    );

    private final DomainMBeanRegistrar jmxRegistrar;
    private final ScrubbingRuntime scrubbingRuntime;

    PlatformExportProcessorFactory(DomainMBeanRegistrar jmxRegistrar) {
        this(jmxRegistrar, ScrubbingRuntime.disabled());
    }

    public PlatformExportProcessorFactory(DomainMBeanRegistrar jmxRegistrar, ScrubbingRuntime scrubbingRuntime) {
        this.jmxRegistrar = jmxRegistrar;
        this.scrubbingRuntime = Objects.requireNonNull(scrubbingRuntime, "scrubbingRuntime");
    }

    public SpanExporter captureExporter(SpanExporter exporter,
                                        TracingSettingsSource acceptedPolicy) {
        if (exporter instanceof ReplacementAwareSpanExporter replacementAware) {
            return replacementAware;
        }
        int exporterRegistrationIndex = exporterCount.incrementAndGet();

        SafeSpanExporter safeExporter = wrapOnce(exporter, acceptedPolicy);
        scrubbingRuntime.installation().acknowledgeExporterWrapped();

        ReplacementAwareSpanExporter stockExporter = new ReplacementAwareSpanExporter(safeExporter);
        if (exporterRegistrationIndex == 1) {
            capturedExporter.set(safeExporter);
            capturedStockExporter.set(stockExporter);

            if (jmxRegistrar != null) {
                jmxRegistrar.setSafeExporter(safeExporter);
                jmxRegistrar.extensionReadiness().markInstalled(PlatformExtensionCapability.SAFE_EXPORTER_INSTALLED);
            }
        }

        return stockExporter;
    }

    SpanExporter captureExporter(SpanExporter exporter) {
        return captureExporter(exporter, TracingSettingsSource.isolatedDefaults());
    }

    private SafeSpanExporter wrapOnce(SpanExporter exporter, TracingSettingsSource acceptedPolicy) {
        if (exporter instanceof SafeSpanExporter safe) {
            if (ownedExporters.contains(safe) && safe.isProtectedBy(scrubbingRuntime)) {
                return safe;
            }

            log.error("[scrubbing] {}: foreign SafeSpanExporter of type {} is present in the exporter "
                            + "customizer chain and cannot be re-wrapped without breaking the "
                            + "single-boundary-pass invariant",
                    SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER, exporter.getClass().getName());

            throw new SanitizerBootstrapException(
                    SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER,
                    "Foreign SafeSpanExporter is not protected by the session scrubbing runtime ("
                            + exporter.getClass().getName() + ")"
            );
        }

        SafeSpanExporter created = new SafeSpanExporter(exporter, acceptedPolicy, scrubbingRuntime);
        ownedExporters.add(created);
        return created;
    }

    public SpanProcessor maybeReplaceExportProcessor(SpanProcessor processor,
                                                     QueueExtensionConfig queueConfig,
                                                     ConfigProperties config) {
        scrubbingRuntime.installation().acknowledgeExportModeResolved();
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
                    exporterCount.get()
            );
            markExportPathProtected();
            return processor;
        }

        SpanExporter exporter = capturedExporter.get();
        if (exporter == null) {
            log.warn("""
                    Platform DROP_OLDEST: captured SpanExporter is null.
                    Falling back — processor left unchanged."""
            );
            return processor;
        }

        if (!(processor instanceof BatchSpanProcessor)) {
            log.warn("""
                    Platform DROP_OLDEST: pipeline processor is {} (not a BatchSpanProcessor).
                    Falling back — passthrough.""",
                    processor.getClass().getName()
            );
            markExportPathProtected();
            return processor;
        }

        ReplacementAwareSpanExporter stockExporter = capturedStockExporter.get();
        if (stockExporter == null) {
            log.warn("Platform DROP_OLDEST: replacement-aware exporter is absent; falling back to stock processor");
            markExportPathProtected();
            return processor;
        }

        stockExporter.suppressReplacementShutdown();
        try {
            processor.shutdown().join(5, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.warn("""
                    Platform DROP_OLDEST: stock BatchSpanProcessor.shutdown() failed: {}.
                    Proceeding with replacement.""",
                    e.getMessage()
            );
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
                    replacement.metricsSnapshot().queueCapacity(), exporter.getClass().getName()
            );
        }

        return replacement;
    }

    private void markExportPathProtected() {
        if (jmxRegistrar != null && capturedExporter.get() != null) {
            jmxRegistrar.extensionReadiness().markInstalled(PlatformExtensionCapability.EXPORT_PATH_PROTECTED);
            scrubbingRuntime.installation().acknowledgeExportPathProtected();
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
                QueueOverflowPolicy.DROP_OLDEST.value()
        );

        return false;
    }

    private static final class ReplacementAwareSpanExporter implements SpanExporter {
        private final SpanExporter delegate;
        private final AtomicBoolean suppressReplacementShutdown = new AtomicBoolean();

        private ReplacementAwareSpanExporter(SpanExporter delegate) {
            this.delegate = delegate;
        }

        private void suppressReplacementShutdown() {
            suppressReplacementShutdown.set(true);
        }

        @Override
        public CompletableResultCode export(@Nonnull Collection<SpanData> spans) {
            return delegate.export(spans);
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            if (suppressReplacementShutdown.compareAndSet(true, false)) {
                return CompletableResultCode.ofSuccess();
            }

            return delegate.shutdown();
        }
    }
}
