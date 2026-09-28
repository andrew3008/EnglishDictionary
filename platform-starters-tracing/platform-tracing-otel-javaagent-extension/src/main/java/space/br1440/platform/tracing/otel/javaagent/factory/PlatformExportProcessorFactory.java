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

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.configuration.QueueExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ExportPipelineLogEvent;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public final class PlatformExportProcessorFactory {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(PlatformExportProcessorFactory.class);
    private static final String SHUTDOWN_KIND_FAILED = "failed";
    private static final String SHUTDOWN_KIND_TIMEOUT = "timeout";

    private final AtomicReference<SafeSpanExporter> capturedExporter = new AtomicReference<>();
    private final AtomicReference<ReplacementAwareSpanExporter> capturedStockExporter = new AtomicReference<>();
    private final AtomicInteger exporterCount = new AtomicInteger();
    private final AtomicBoolean dropOldestEnabledLogged = new AtomicBoolean();
    private final Set<SafeSpanExporter> ownedExporters = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>())
    );

    private final PlatformTracingMBeanRegistrar jmxRegistrar;
    private final ScrubbingRuntime scrubbingRuntime;

    public PlatformExportProcessorFactory(PlatformTracingMBeanRegistrar jmxRegistrar, ScrubbingRuntime scrubbingRuntime) {
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
        scrubbingRuntime.acknowledgeExporterWrapped();

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

            log.log(ExportPipelineLogEvent.FOREIGN_SAFE_SPAN_EXPORTER_REWRAP_REJECTED,
                    fields -> fields.putString(AgentLogField.TYPE, exporter.getClass().getName()));

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
        scrubbingRuntime.acknowledgeExportModeResolved();
        if (isExplicitUpstream(queueConfig)) {
            markExportPathProtected();
            return processor;
        }

        if (exporterCount.get() > 1) {
            log.log(ExportPipelineLogEvent.MULTIPLE_SPAN_EXPORTERS_FALLBACK,
                    fields -> fields.putLong(AgentLogField.COUNT, exporterCount.get()));
            markExportPathProtected();
            return processor;
        }

        SpanExporter exporter = capturedExporter.get();
        if (exporter == null) {
            log.log(ExportPipelineLogEvent.CAPTURED_SPAN_EXPORTER_NULL_FALLBACK);
            return processor;
        }

        if (!(processor instanceof BatchSpanProcessor)) {
            log.log(ExportPipelineLogEvent.NON_BATCH_SPAN_PROCESSOR_FALLBACK,
                    fields -> fields.putString(AgentLogField.TYPE, processor.getClass().getName()));
            markExportPathProtected();
            return processor;
        }

        ReplacementAwareSpanExporter stockExporter = capturedStockExporter.get();
        if (stockExporter == null) {
            log.log(ExportPipelineLogEvent.REPLACEMENT_AWARE_EXPORTER_ABSENT_FALLBACK);
            markExportPathProtected();
            return processor;
        }

        stockExporter.suppressReplacementShutdown();
        try {
            // join не бросает исключение, если shutdown завершился ошибкой или не уложился в таймаут.
            logUnsuccessfulStockShutdown(processor.shutdown().join(5, TimeUnit.SECONDS));
        } catch (RuntimeException e) {
            log.log(ExportPipelineLogEvent.STOCK_BATCH_SPAN_PROCESSOR_SHUTDOWN_FAILED, fields -> fields.cause(e));
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
            log.log(ExportPipelineLogEvent.DROP_OLDEST_EXPORT_PROCESSOR_ENABLED,
                    fields -> fields.putLong(AgentLogField.COUNT, replacement.metricsSnapshot().queueCapacity()));
        }

        return replacement;
    }

    static void logUnsuccessfulStockShutdown(CompletableResultCode result) {
        if (result != null && result.isSuccess()) {
            return;
        }

        String kind = result != null && result.isDone() ? SHUTDOWN_KIND_FAILED : SHUTDOWN_KIND_TIMEOUT;
        log.log(ExportPipelineLogEvent.STOCK_BATCH_SPAN_PROCESSOR_SHUTDOWN_UNSUCCESSFUL,
                fields -> fields.putString(AgentLogField.KIND, kind));
    }

    private void markExportPathProtected() {
        if (jmxRegistrar != null && capturedExporter.get() != null) {
            jmxRegistrar.extensionReadiness().markInstalled(PlatformExtensionCapability.EXPORT_PATH_PROTECTED);
            scrubbingRuntime.acknowledgeExportPathProtected();
        }
    }

    private static boolean isExplicitUpstream(QueueExtensionConfig queueConfig) {
        String configuredPolicy = queueConfig.overflowPolicy();
        String normalized = configuredPolicy.trim().toUpperCase(Locale.ROOT);
        if (QueueOverflowPolicy.UPSTREAM.value().equals(normalized)) {
            return true;
        }

        if (QueueOverflowPolicy.DROP_OLDEST.value().equals(normalized)) {
            return false;
        }

        String diagnosticValue = StringUtils.hasText(configuredPolicy) ? configuredPolicy : "<blank>";
        log.log(ExportPipelineLogEvent.UNKNOWN_OVERFLOW_POLICY_DEFAULTED,
                fields -> fields.putString(AgentLogField.NAME, diagnosticValue));

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
