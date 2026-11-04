package space.br1440.platform.tracing.otel.javaagent.factory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.guard.OtlpRequestSizeLimitingSpanExporter;
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

        SafeSpanExporter safeExporter = new SafeSpanExporter(
                new OtlpRequestSizeLimitingSpanExporter(
                        exporter,
                        Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES)
                ),
                acceptedPolicy,
                scrubbingRuntime
        );
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
        PlatformDropOldestExportSpanProcessor replacement = selectExportProcessor(processor, config);
        try {
            jmxRegistrar.setExportProcessor(replacement);
            markExportPathProtected();
            log.log(ExportPipelineLogEvent.DROP_OLDEST_EXPORT_PROCESSOR_ENABLED,fields -> fields
                    .putLong(AgentLogField.COUNT, replacement.metricsSnapshot().queueCapacity()));
            return replacement;
        } catch (Throwable failure) {
            try {
                replacement.shutdown();
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }

            throw failure;
        }
    }

    private PlatformDropOldestExportSpanProcessor selectExportProcessor(SpanProcessor processor,
                                                                        ConfigProperties config) {
        if (capturedExporters.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one captured span exporter, found " + capturedExporters.size()
            );
        }

        if (!(processor instanceof BatchSpanProcessor stockProcessor)) {
            throw new IllegalStateException("Expected BatchSpanProcessor, found " + processor.getClass().getName());
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
        stockExporter.replacement = replacement;
        return replacement;
    }

    private void markExportPathProtected() {
        jmxRegistrar.extensionReadiness().markInstalled(PlatformExtensionCapability.EXPORT_PATH_PROTECTED);
        scrubbingRuntime.acknowledgeExportPathProtected();
    }

    private static final class ReplacementAwareSpanExporter implements SpanExporter {
        private final SafeSpanExporter delegate;
        private final AtomicBoolean suppressReplacementShutdown = new AtomicBoolean();
        private volatile PlatformDropOldestExportSpanProcessor replacement;

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

            PlatformDropOldestExportSpanProcessor owner = replacement;
            return (owner == null) ? delegate.shutdown() : owner.shutdown();
        }
    }
}
