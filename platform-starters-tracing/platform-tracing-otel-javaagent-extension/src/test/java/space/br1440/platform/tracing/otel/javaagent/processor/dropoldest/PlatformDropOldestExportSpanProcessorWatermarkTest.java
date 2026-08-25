package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;

class PlatformDropOldestExportSpanProcessorWatermarkTest {

    @Test
    void concurrentFlushRequestsUseAtMostTwoCoalescedGenerations() throws InterruptedException {
        ControlledExporter exporter = new ControlledExporter();
        PlatformDropOldestExportSpanProcessor processor = processor(exporter, 16, 2);
        SdkTracerProvider provider = provider(processor);
        try {
            Tracer tracer = provider.get("two-generations");
            endSpans(tracer, "first", 2);
            CompletableResultCode active = processor.forceFlush();
            assertThat(exporter.firstExportStarted.await(5, TimeUnit.SECONDS)).isTrue();

            endSpans(tracer, "second", 3);
            CompletableResultCode queued = processor.forceFlush();
            endSpans(tracer, "third", 2);
            CompletableResultCode coalesced = processor.forceFlush();

            assertThat(coalesced).isSameAs(queued);
            assertThat(queued).isNotSameAs(active);
            assertThat(processor.pendingFlushGenerationCount()).isEqualTo(2);

            exporter.firstExport.succeed();
            assertThat(active.join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(queued.join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(processor.pendingFlushGenerationCount()).isZero();
            assertThat(exporter.exported).hasSize(7);
        } finally {
            exporter.firstExport.succeed();
            provider.shutdown().join(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void overflowOfProtectedEntryFailsOwningFlush() throws InterruptedException {
        ControlledExporter exporter = new ControlledExporter();
        PlatformDropOldestExportSpanProcessor processor = processor(exporter, 2, 1);
        SdkTracerProvider provider = provider(processor);
        try {
            Tracer tracer = provider.get("protected-drop");
            endSpans(tracer, "in-flight", 1);
            assertThat(exporter.firstExportStarted.await(5, TimeUnit.SECONDS)).isTrue();

            endSpans(tracer, "protected", 2);
            CompletableResultCode flush = processor.forceFlush();
            endSpans(tracer, "overflow", 1);

            assertThat(flush.join(5, TimeUnit.SECONDS).isSuccess()).isFalse();
            assertThat(processor.metricsSnapshot().droppedOverflowTotal()).isEqualTo(1);
        } finally {
            exporter.firstExport.succeed();
            provider.shutdown().join(5, TimeUnit.SECONDS);
        }
    }

    private static PlatformDropOldestExportSpanProcessor processor(
            SpanExporter exporter,
            int queueSize,
            int batchSize
    ) {
        return PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(queueSize)
                .maxExportBatchSize(batchSize)
                .scheduleDelay(Duration.ofHours(1))
                .exportTimeout(Duration.ofSeconds(30))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static SdkTracerProvider provider(PlatformDropOldestExportSpanProcessor processor) {
        return SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
    }

    private static void endSpans(Tracer tracer, String prefix, int count) {
        for (int index = 0; index < count; index++) {
            tracer.spanBuilder(prefix + '-' + index).startSpan().end();
        }
    }

    private static final class ControlledExporter implements SpanExporter {
        private final CountDownLatch firstExportStarted = new CountDownLatch(1);
        private final CompletableResultCode firstExport = new CompletableResultCode();
        private final AtomicInteger exportCalls = new AtomicInteger();
        private final List<SpanData> exported = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exported.addAll(spans);
            if (exportCalls.incrementAndGet() == 1) {
                firstExportStarted.countDown();
                return firstExport;
            }
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
