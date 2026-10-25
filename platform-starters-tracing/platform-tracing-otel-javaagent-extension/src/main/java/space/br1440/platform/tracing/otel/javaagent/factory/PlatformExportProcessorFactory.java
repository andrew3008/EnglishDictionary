package space.br1440.platform.tracing.otel.javaagent.factory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.Nonnull;

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
    private static final Duration STOCK_PROCESSOR_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final List<ReplacementAwareSpanExporter> capturedExporters = new ArrayList<>();
    private final PlatformTracingMBeanRegistrar jmxRegistrar;
    private final ScrubbingRuntime scrubbingRuntime;

    public PlatformExportProcessorFactory(PlatformTracingMBeanRegistrar jmxRegistrar, ScrubbingRuntime scrubbingRuntime) {
        this.jmxRegistrar = Objects.requireNonNull(jmxRegistrar, "jmxRegistrar");
        this.scrubbingRuntime = Objects.requireNonNull(scrubbingRuntime, "scrubbingRuntime");
    }

    public SpanExporter captureExporter(SpanExporter exporter,
                                        TracingSettingsSource acceptedPolicy) {
        if (exporter instanceof ReplacementAwareSpanExporter replacementAware) {
            return replacementAware;
        }

        rejectForeignSafeExporter(exporter);

        SafeSpanExporter safeExporter = new SafeSpanExporter(exporter, acceptedPolicy, scrubbingRuntime);
        scrubbingRuntime.acknowledgeExporterWrapped();

        ReplacementAwareSpanExporter stockExporter = new ReplacementAwareSpanExporter(safeExporter);
        capturedExporters.add(stockExporter);
        if (capturedExporters.size() == 1) {
            jmxRegistrar.setSafeExporter(safeExporter);
            jmxRegistrar.extensionReadiness().markInstalled(PlatformExtensionCapability.SAFE_EXPORTER_INSTALLED);
        }

        return stockExporter;
    }

    private static void rejectForeignSafeExporter(SpanExporter exporter) {
        if (!(exporter instanceof SafeSpanExporter)) {
            return;
        }

        String type = exporter.getClass().getName();
        log.log(ExportPipelineLogEvent.FOREIGN_SAFE_SPAN_EXPORTER_REWRAP_REJECTED,
                fields -> fields.putString(AgentLogField.TYPE, type));

        throw new SanitizerBootstrapException(
                SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER,
                "Foreign SafeSpanExporter is not protected by the session scrubbing runtime (%s)".formatted(type)
        );
    }

    public SpanProcessor maybeReplaceExportProcessor(SpanProcessor processor,
                                                     ConfigProperties config) {
        scrubbingRuntime.acknowledgeExportModeResolved();
        SpanProcessor exportProcessor = selectExportProcessor(processor, config);
        markExportPathProtected();
        return exportProcessor;
    }

    private SpanProcessor selectExportProcessor(SpanProcessor processor,
                                                ConfigProperties config) {
        if (capturedExporters.size() > 1) {
            log.log(ExportPipelineLogEvent.MULTIPLE_SPAN_EXPORTERS_FALLBACK,
                    fields -> fields.putLong(AgentLogField.COUNT, capturedExporters.size()));
            return processor;
        }

        if (capturedExporters.isEmpty()) {
            log.log(ExportPipelineLogEvent.CAPTURED_SPAN_EXPORTER_NULL_FALLBACK);
            return processor;
        }

        if (!(processor instanceof BatchSpanProcessor stockProcessor)) {
            log.log(ExportPipelineLogEvent.NON_BATCH_SPAN_PROCESSOR_FALLBACK,
                    fields -> fields.putString(AgentLogField.TYPE, processor.getClass().getName()));
            return processor;
        }

        return replaceStockProcessor(stockProcessor, capturedExporters.getFirst(), config);
    }

    private PlatformDropOldestExportSpanProcessor replaceStockProcessor(BatchSpanProcessor stockProcessor,
                                                                        ReplacementAwareSpanExporter stockExporter,
                                                                        ConfigProperties config) {
        PlatformDropOldestExportSpanProcessor.Builder replacementBuilder = PlatformDropOldestExportSpanProcessor
                .builder(stockExporter.safeExporter())
                .readBspConfigFrom(config)
                .validate();

        stockExporter.suppressReplacementShutdown();
        CompletableResultCode shutdownResult = stockProcessor.shutdown()
                .join(STOCK_PROCESSOR_SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        if (!shutdownResult.isDone()) {
            throw new IllegalStateException("Stock BatchSpanProcessor shutdown did not complete before replacement");
        }

        if (!shutdownResult.isSuccess()) {
            log.log(ExportPipelineLogEvent.STOCK_BATCH_SPAN_PROCESSOR_SHUTDOWN_UNSUCCESSFUL);
        }

        PlatformDropOldestExportSpanProcessor replacement = replacementBuilder.build();
        jmxRegistrar.setExportProcessor(replacement);
        log.log(ExportPipelineLogEvent.DROP_OLDEST_EXPORT_PROCESSOR_ENABLED,
                fields -> fields.putLong(AgentLogField.COUNT, replacement.metricsSnapshot().queueCapacity()));

        return replacement;
    }

    private void markExportPathProtected() {
        if (capturedExporters.isEmpty()) {
            return;
        }

        jmxRegistrar.extensionReadiness().markInstalled(PlatformExtensionCapability.EXPORT_PATH_PROTECTED);
        scrubbingRuntime.acknowledgeExportPathProtected();
    }

    private static final class ReplacementAwareSpanExporter implements SpanExporter {
        private final SafeSpanExporter delegate;
        private final AtomicBoolean suppressReplacementShutdown = new AtomicBoolean();

        private ReplacementAwareSpanExporter(SafeSpanExporter delegate) {
            this.delegate = delegate;
        }

        private SafeSpanExporter safeExporter() {
            return delegate;
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
