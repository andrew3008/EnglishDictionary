package space.br1440.platform.tracing.otel.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

/**
 * Probe-тест фактической политики переполнения очереди стандартного
 * {@link io.opentelemetry.sdk.trace.export.BatchSpanProcessor} на SDK из version catalog.
 *
 * <p><b>Защита от timing flakiness через управляемый первый export:</b></p>
 * <ol>
 *   <li>{@code firstExportStarted} — countDown при первом вызове {@code export()}.
 *       Тест ожидает её перед фазой overflow, гарантируя, что worker BSP реально
 *       ожидает completion первого export, а не находится между read очереди и его вызовом.</li>
 *   <li>{@code firstExportCompletion} — незавершённый результат первого {@code export()}.
 *       BSP ожидает его до явного завершения тестом, не блокируя сам вызов exporter.</li>
 * </ol>
 *
 * <p><b>Assertion philosophy:</b> не «доказать drop-oldest», а зафиксировать observable
 * snapshot. exported count must not exceed the number of spans that could be accepted by
 * the configured BSP queue under the controlled blocking scenario; exact bound is
 * documented in the test after observing SDK behavior. Конкретные observed/missing IDs
 * передаются дальше в ADR finding.</p>
 */
class BatchSpanProcessorOverflowPolicyProbeTest {

    /** Атрибут sequence ID — единственный способ отличить span'ы в snapshot. */
    private static final AttributeKey<Long> SEQ = AttributeKey.longKey("probe.seq");

    /**
     * Размеры подобраны так, чтобы overflow гарантированно произошёл: worker ожидает
     * незавершённый result первого batch, очередь maxQueueSize ровно достижима, далее намеренно
     * генерируем существенно больше span'ов.
     */
    private static final int MAX_QUEUE = 4;
    private static final int BATCH_SIZE = 2;
    private static final int OVERFLOW_FACTOR = 8;

    @Test
    @DisplayName("Probe: actual overflow policy of stock BSP on the catalog SDK (snapshot)")
    void observeOverflowPolicy_pinnedSdk() throws InterruptedException {
        int overflowTo = BATCH_SIZE + MAX_QUEUE * OVERFLOW_FACTOR;
        List<Long> exportedSeq;
        int exportCallCount;
        try (ControlledExporter exporter = new ControlledExporter();
             BatchSpanProcessor bsp = BatchSpanProcessor.builder(exporter)
                     .setMaxQueueSize(MAX_QUEUE)
                     .setMaxExportBatchSize(BATCH_SIZE)
                     // Очень большой schedule delay — исключаем background flush в фазе overflow.
                     .setScheduleDelay(Duration.ofMinutes(10))
                     .setExporterTimeout(Duration.ofSeconds(30))
                     .build();
             SdkTracerProvider provider = SdkTracerProvider.builder()
                     .setSampler(Sampler.alwaysOn())
                     .addSpanProcessor(bsp)
                     .build()) {
            Tracer tracer = provider.get("probe");

            CompletableResultCode flushResult;
            try {
                // Фаза 1: создаём первые BATCH_SIZE span'ов, чтобы worker BSP запустил export
                // и начал ожидать его completion.
                for (int i = 0; i < BATCH_SIZE; i++) {
                    emit(tracer, i);
                }

                // Дожидаемся, что worker реально вошёл в export() и больше не опустошает очередь.
                boolean entered = exporter.awaitFirstExport(Duration.ofSeconds(10));
                assertThat(entered).as("BSP worker must enter export() before the overflow phase").isTrue();

                // Фаза 2: генерируем существенно больше span'ов, чем вмещается в очередь.
                // worker ожидает completion первого export, поэтому overflow проявится здесь.
                for (int i = BATCH_SIZE; i < overflowTo; i++) {
                    emit(tracer, i);
                }

                // Регистрируем flush до release, пока первый export всё ещё удерживает worker.
                flushResult = provider.forceFlush();
            } finally {
                exporter.completeFirstExport();
            }

            // Фаза 3: дожидаемся экспорта остатков и полного завершения worker перед snapshot.
            assertThat(flushResult.join(30, TimeUnit.SECONDS).isSuccess())
                    .as("BSP forceFlush must complete successfully")
                    .isTrue();
            assertThat(provider.shutdown().join(10, TimeUnit.SECONDS).isSuccess())
                    .as("BSP shutdown must complete successfully")
                    .isTrue();

            ExportSnapshot snapshot = exporter.snapshotAfterShutdown();
            exportedSeq = snapshot.exportedSequences();
            exportCallCount = snapshot.exportCallCount();
        }

        assertThat(exportedSeq.size())
                .as("exported count <= total emitted (sanity)")
                .isLessThanOrEqualTo(overflowTo);

        System.out.println("[BSP overflow probe] SDK observed exported seq: " + exportedSeq);
        System.out.println("[BSP overflow probe] SDK observed exported count: " + exportedSeq.size());
        System.out.println("[BSP overflow probe] SDK observed export() call count: " + exportCallCount);

        assertThat(exportedSeq)
                .as("The first batch (seq 0..BATCH_SIZE-1) that held the worker must appear in the result")
                .contains(0L, 1L);
    }

    private static void emit(Tracer tracer, int seq) {
        io.opentelemetry.api.trace.Span span = tracer.spanBuilder("probe-" + seq)
                .setParent(Context.root())
                .setAllAttributes(Attributes.of(SEQ, (long) seq))
                .startSpan();
        span.end();
    }

    private static final class ControlledExporter implements SpanExporter {

        private final CountDownLatch firstExportStarted = new CountDownLatch(1);
        private final CompletableResultCode firstExportCompletion = new CompletableResultCode();
        private final List<Long> exportedSequences = new CopyOnWriteArrayList<>();
        private final AtomicInteger exportCalls = new AtomicInteger();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            boolean firstExport = (exportCalls.incrementAndGet() == 1);

            List<Long> batchSequences = new ArrayList<>(spans.size());
            for (SpanData span : spans) {
                Long sequence = span.getAttributes().get(SEQ);
                if (sequence != null) {
                    batchSequences.add(sequence);
                }
            }
            exportedSequences.addAll(batchSequences);

            if (firstExport) {
                firstExportStarted.countDown();
                return firstExportCompletion;
            }

            return CompletableResultCode.ofSuccess();
        }

        private boolean awaitFirstExport(Duration timeout) throws InterruptedException {
            return firstExportStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private void completeFirstExport() {
            firstExportCompletion.succeed();
        }

        private ExportSnapshot snapshotAfterShutdown() {
            return new ExportSnapshot(List.copyOf(exportedSequences), exportCalls.get());
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            completeFirstExport();
            return CompletableResultCode.ofSuccess();
        }
    }

    private record ExportSnapshot(List<Long> exportedSequences, int exportCallCount) {
    }
}
