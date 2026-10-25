package space.br1440.platform.tracing.otel.javaagent.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class PlatformExportProcessorFactoryShutdownTest {

    private static final String DROP_OLDEST_WORKER_NAME = "platform-tracing-drop-oldest-exporter";

    @RegisterExtension
    AgentLogCapture capture = new AgentLogCapture();

    @Test
    void real_stock_shutdown_keeps_delegate_open_until_replacement_shutdown() {
        // given
        PlatformExportProcessorFactory factory = factory(
                new PlatformTracingMBeanRegistrar(AgentInstanceId.random())
        );
        AtomicInteger delegateShutdowns = new AtomicInteger();
        SpanExporter captured = factory.captureExporter(exporter(delegateShutdowns), TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));
        BatchSpanProcessor stock = BatchSpanProcessor.builder(captured).build();

        try (stock) {
            // when
            SpanProcessor replacement =
                    factory.maybeReplaceExportProcessor(stock, config(Map.of()));

            try (replacement) {
                // then
                assertThat(delegateShutdowns).hasValue(0);
                assertThat(capture.events())
                        .noneMatch(event -> "STOCK_BATCH_SPAN_PROCESSOR_SHUTDOWN_UNSUCCESSFUL"
                                .equals(event.eventName())
                        );

                // when
                CompletableResultCode shutdown = replacement.shutdown().join(1, TimeUnit.SECONDS);

                // then
                assertThat(shutdown.isSuccess()).isTrue();
                assertThat(delegateShutdowns).hasValue(1);
            }
        }
    }

    @Test
    void invalid_bsp_config_is_rejected_before_stock_shutdown_or_wrapper_mutation() {
        // given
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(AgentInstanceId.random());
        PlatformExportProcessorFactory factory = factory(registrar);
        AtomicInteger delegateShutdowns = new AtomicInteger();
        SpanExporter captured = factory.captureExporter(exporter(delegateShutdowns), TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));
        BatchSpanProcessor stock = mock(BatchSpanProcessor.class);
        ConfigProperties invalid = config(Map.of("otel.bsp.max.queue.size", "0"));

        // when
        Throwable failure = catchThrowable(
                () -> factory.maybeReplaceExportProcessor(stock, invalid)
        );

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxQueueSize");
        verify(stock, never()).shutdown();
        assertThat(captured.shutdown().join(1, TimeUnit.SECONDS).isSuccess()).isTrue();
        assertThat(delegateShutdowns).hasValue(1);
        assertThat(registrar.extensionReadiness().snapshot().has(
                PlatformExtensionCapability.EXPORT_PATH_PROTECTED)).isFalse();
    }

    @Test
    void unfinished_stock_shutdown_rejects_replacement() {
        // given
        PlatformTracingMBeanRegistrar registrar = spy(
                new PlatformTracingMBeanRegistrar(AgentInstanceId.random())
        );
        PlatformExportProcessorFactory factory = factory(registrar);
        factory.captureExporter(exporter(new AtomicInteger()), TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));
        BatchSpanProcessor stock = mock(BatchSpanProcessor.class);
        CompletableResultCode unfinished = new CompletableResultCode();
        when(stock.shutdown()).thenReturn(unfinished);
        long workerCountBefore = dropOldestWorkerCount();

        try {
            // when
            Throwable failure = catchThrowable(
                    () -> factory.maybeReplaceExportProcessor(stock, config(Map.of()))
            );

            // then
            assertThat(failure)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Stock BatchSpanProcessor shutdown did not complete before replacement");
            verify(stock).shutdown();
            verify(registrar, never()).setExportProcessor(any());
            assertThat(dropOldestWorkerCount()).isEqualTo(workerCountBefore);
            assertThat(registrar.extensionReadiness().snapshot().has(
                    PlatformExtensionCapability.EXPORT_PATH_PROTECTED)).isFalse();
            assertThat(capture.events())
                    .noneMatch(event -> "STOCK_BATCH_SPAN_PROCESSOR_SHUTDOWN_UNSUCCESSFUL"
                            .equals(event.eventName()));
            assertThat(capture.events())
                    .noneMatch(event -> "DROP_OLDEST_EXPORT_PROCESSOR_ENABLED".equals(event.eventName()));
        } finally {
            unfinished.succeed();
        }
    }

    @Test
    void thrown_stock_shutdown_failure_is_propagated_without_replacement() {
        // given
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(AgentInstanceId.random());
        PlatformExportProcessorFactory factory = factory(registrar);
        factory.captureExporter(exporter(new AtomicInteger()), TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));
        BatchSpanProcessor stock = mock(BatchSpanProcessor.class);
        IllegalStateException marker = new IllegalStateException("stock shutdown failed");
        when(stock.shutdown()).thenThrow(marker);

        // when
        Throwable failure = catchThrowable(
                () -> factory.maybeReplaceExportProcessor(stock, config(Map.of()))
        );

        // then
        assertThat(failure).isSameAs(marker);
        verify(stock).shutdown();
        assertThat(registrar.extensionReadiness().snapshot().has(
                PlatformExtensionCapability.EXPORT_PATH_PROTECTED)).isFalse();
        assertThat(capture.events())
                .noneMatch(event -> "DROP_OLDEST_EXPORT_PROCESSOR_ENABLED".equals(event.eventName()));
    }

    @Test
    void completed_failed_stock_shutdown_logs_warning_and_builds_replacement() {
        // given
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(AgentInstanceId.random());
        PlatformExportProcessorFactory factory = factory(registrar);
        factory.captureExporter(exporter(new AtomicInteger()), TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));
        BatchSpanProcessor stock = mock(BatchSpanProcessor.class);
        when(stock.shutdown()).thenReturn(CompletableResultCode.ofFailure());

        // when
        SpanProcessor replacement =
                factory.maybeReplaceExportProcessor(stock, config(Map.of()));

        try {
            // then
            assertThat(replacement).isInstanceOf(PlatformDropOldestExportSpanProcessor.class);
            verify(stock).shutdown();
            assertThat(registrar.extensionReadiness().snapshot().has(
                    PlatformExtensionCapability.EXPORT_PATH_PROTECTED)).isTrue();
            assertThat(capture.events())
                    .filteredOn(event -> "STOCK_BATCH_SPAN_PROCESSOR_SHUTDOWN_UNSUCCESSFUL"
                            .equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.fields()).isEmpty();
                        assertThat(event.errorType()).isNull();
                    });
        } finally {
            replacement.shutdown().join(1, TimeUnit.SECONDS);
        }
    }

    private static PlatformExportProcessorFactory factory(PlatformTracingMBeanRegistrar registrar) {
        return new PlatformExportProcessorFactory(registrar, ScrubbingRuntimeTestFixtures.disabled());
    }

    private static ConfigProperties config(Map<String, String> values) {
        return DefaultConfigProperties.createFromMap(values);
    }

    private static SpanExporter exporter(AtomicInteger shutdowns) {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                shutdowns.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }
        };
    }

    private static long dropOldestWorkerCount() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> DROP_OLDEST_WORKER_NAME.equals(thread.getName()))
                .count();
    }
}
