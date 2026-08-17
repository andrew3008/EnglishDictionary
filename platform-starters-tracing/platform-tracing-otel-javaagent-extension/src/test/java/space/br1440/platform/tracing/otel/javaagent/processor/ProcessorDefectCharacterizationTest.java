package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;

/** Фиксирует подтверждённые дефекты до изменения production-кода owning slices. */
class ProcessorDefectCharacterizationTest {

    @RegisterExtension
    AgentLogCapture capture = new AgentLogCapture();

    @Test
    void compositeDiagnosticsDoNotDiscloseRawSpanOrExceptionText() {
        PlatformCompositeSpanProcessor composite =
                new PlatformCompositeSpanProcessor(List.of(new SensitiveFailureProcessor()));
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(composite)
                .build();
        try {
            provider.get("characterization")
                    .spanBuilder("customer-secret-span-name")
                    .startSpan()
                    .end();

            assertThat(capture.events())
                    .anySatisfy(event -> assertThat(event.eventCode())
                            .isEqualTo(AgentEventCodes.EXPORT_CALLBACK));
            assertThat(capture.rendered())
                    .doesNotContain("customer-secret-span-name", "customer-secret-exception");
            assertThat(capture.events()).allSatisfy(event -> {
                assertThat(event.fields().toString())
                        .doesNotContain("customer-secret-span-name", "customer-secret-exception");
                if (event.throwableDigest() != null) {
                    assertThat(event.throwableDigest())
                            .doesNotContain("customer-secret-span-name", "customer-secret-exception");
                }
            });
        } finally {
            provider.close();
        }
    }

    @Test
    void watchdogKeepsSameSpanIdIsolatedByTrace() {
        SpanWatchdogProcessor watchdog =
                new SpanWatchdogProcessor(Duration.ofHours(1), Duration.ofHours(2), System::nanoTime);
        ReadWriteSpan first = span(
                "00000000000000000000000000000001", "0000000000000001");
        ReadWriteSpan second = span(
                "00000000000000000000000000000002", "0000000000000001");
        try {
            watchdog.onStart(Context.root(), first);
            watchdog.onStart(Context.root(), second);

            assertThat(watchdog.getActiveSpanCount()).isEqualTo(2);
            assertThat(watchdog.getActiveTraceCount()).isEqualTo(2);

            watchdog.onEnd(first);
            assertThat(watchdog.getActiveSpanCount()).isEqualTo(1);
            assertThat(watchdog.getActiveTraceCount()).isEqualTo(1);
            watchdog.onEnd(second);
            assertThat(watchdog.getActiveSpanCount()).isZero();
            assertThat(watchdog.getActiveTraceCount()).isZero();
        } finally {
            watchdog.shutdown();
        }
    }

    @Test
    void dropOldestDefersExporterShutdownUntilTimedOutExportCompletes()
            throws InterruptedException {
        PendingExporter exporter = new PendingExporter();
        PlatformDropOldestExportSpanProcessor processor =
                PlatformDropOldestExportSpanProcessor.builder(exporter)
                        .maxQueueSize(8)
                        .maxExportBatchSize(1)
                        .scheduleDelay(Duration.ofHours(1))
                        .exportTimeout(Duration.ofMillis(20))
                        .shutdownTimeout(Duration.ofSeconds(1))
                        .build();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(processor)
                .build();
        try {
            Tracer tracer = provider.get("characterization");
            tracer.spanBuilder("pending-export").startSpan().end();
            assertThat(exporter.exportStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitExportTimeout(processor);

            CompletableResultCode shutdown = processor.shutdown();
            assertThat(shutdown.join(5, TimeUnit.SECONDS).isSuccess()).isFalse();
            assertThat(exporter.shutdownCalled.getCount()).isOne();

            exporter.pendingExport.succeed();
            assertThat(exporter.shutdownCalled.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(exporter.shutdownWhileExportPending).isFalse();
            assertThat(exporter.shutdownCalls).isEqualTo(1);
        } finally {
            exporter.pendingExport.succeed();
            provider.shutdown().join(5, TimeUnit.SECONDS);
        }
    }

    private static ReadWriteSpan span(String traceId, String spanId) {
        ReadWriteSpan span = mock(ReadWriteSpan.class);
        SpanContext context = SpanContext.create(
                traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
        when(span.getSpanContext()).thenReturn(context);
        return span;
    }

    private static void awaitExportTimeout(PlatformDropOldestExportSpanProcessor processor) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (processor.metricsSnapshot().timeoutsTotal() == 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertThat(processor.metricsSnapshot().timeoutsTotal()).isEqualTo(1);
    }

    private static final class SensitiveFailureProcessor implements SpanProcessor, ProcessorDiagnosticIdentity {
        @Override
        public String diagnosticId() {
            return "test_sensitive_failure";
        }
        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            throw new IllegalStateException("customer-secret-exception");
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(ReadableSpan span) {
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }
    }

    private static final class PendingExporter implements SpanExporter {
        private final CountDownLatch exportStarted = new CountDownLatch(1);
        private final CountDownLatch shutdownCalled = new CountDownLatch(1);
        private final CompletableResultCode pendingExport = new CompletableResultCode();
        private final AtomicBoolean exportPending = new AtomicBoolean();
        private volatile boolean shutdownWhileExportPending;
        private volatile int shutdownCalls;

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exportPending.set(true);
            exportStarted.countDown();
            pendingExport.whenComplete(() -> exportPending.set(false));
            return pendingExport;
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            shutdownCalls++;
            shutdownWhileExportPending = exportPending.get();
            shutdownCalled.countDown();
            return CompletableResultCode.ofSuccess();
        }
    }
}
