package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

class PlatformDropOldestExportSpanProcessorWatermarkTest {

    @Test
    void concurrentFlushRequestsUseAtMostTwoCoalescedGenerations() throws InterruptedException {
        // given
        ControlledExporter exporter = new ControlledExporter();
        PlatformDropOldestExportSpanProcessor processor = processor(exporter, 16, 2);
        SdkTracerProvider provider = provider(processor);
        try {
            Tracer tracer = provider.get("two-generations");
            // when
            endSpans(tracer, "first", 2);
            CompletableResultCode active = processor.forceFlush();
            assertThat(exporter.firstExportStarted.await(5, TimeUnit.SECONDS)).isTrue();

            endSpans(tracer, "second", 3);
            CompletableResultCode queued = processor.forceFlush();
            endSpans(tracer, "third", 2);
            CompletableResultCode coalesced = processor.forceFlush();

            // then
            assertThat(coalesced).isSameAs(queued);
            assertThat(queued).isNotSameAs(active);

            exporter.firstExport.succeed();
            assertThat(active.join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(queued.join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(exporter.exported).hasSize(7);
        } finally {
            exporter.firstExport.succeed();
            provider.shutdown().join(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void overflowOfProtectedEntryFailsOwningFlush() throws InterruptedException {
        // given
        ControlledExporter exporter = new ControlledExporter();
        PlatformDropOldestExportSpanProcessor processor = processor(exporter, 2, 1);
        SdkTracerProvider provider = provider(processor);
        try {
            Tracer tracer = provider.get("protected-drop");
            // when
            endSpans(tracer, "in-flight", 1);
            assertThat(exporter.firstExportStarted.await(5, TimeUnit.SECONDS)).isTrue();

            endSpans(tracer, "protected", 2);
            CompletableResultCode flush = processor.forceFlush();
            endSpans(tracer, "overflow", 1);

            // then
            assertThat(flush.join(5, TimeUnit.SECONDS).isDone()).isTrue();
            assertThat(flush.isSuccess()).isFalse();
            assertThat(processor.metricsSnapshot().droppedOverflowTotal()).isEqualTo(1);
        } finally {
            exporter.firstExport.succeed();
            provider.shutdown().join(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedBatchContainingAnEarlierWatermarkFailsThatFlush() {
        // given
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(16, 16, metrics);
        for (int index = 0; index < 5; index++) {
            buffer.admit(spanData());
        }
        CompletableResultCode flush = buffer.requestFlush();
        for (int index = 0; index < 5; index++) {
            buffer.admit(spanData());
        }

        // when
        WorkerAction action =
                buffer.awaitNextAction(Duration.ofHours(1).toNanos(), System.nanoTime());
        assertThat(action).isInstanceOf(WorkerAction.ExportBatch.class);
        SpanQueue.Batch batch = ((WorkerAction.ExportBatch) action).batch();
        assertThat(batch.spans()).hasSize(10);
        buffer.completeExport(batch, false, false);

        // then
        assertThat(flush.isDone()).isTrue();
        assertThat(flush.isSuccess()).isFalse();
    }

    @Test
    void dropOfTheLastProtectedSpanFailsTheOwningFlushBeforeTheOlderBatchSettles() {
        // given
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(2, 1, metrics);
        buffer.admit(spanData());
        WorkerAction action =
                buffer.awaitNextAction(Duration.ofHours(1).toNanos(), System.nanoTime());
        assertThat(action).isInstanceOf(WorkerAction.ExportBatch.class);
        SpanQueue.Batch inFlight = ((WorkerAction.ExportBatch) action).batch();
        buffer.admit(spanData());
        CompletableResultCode flush = buffer.requestFlush();
        buffer.admit(spanData());

        // when
        buffer.admit(spanData());

        // then
        assertThat(flush.isDone()).isTrue();
        assertThat(flush.isSuccess()).isFalse();
        assertThat(metrics.droppedOverflowTotal()).isEqualTo(1);
        buffer.completeExport(inFlight, true, false);
        assertThat(flush.isSuccess()).isFalse();
    }

    private static SpanData spanData() {
        SpanData spanData = Mockito.mock(SpanData.class);
        Mockito.when(spanData.getAttributes()).thenReturn(Attributes.empty());
        return spanData;
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
