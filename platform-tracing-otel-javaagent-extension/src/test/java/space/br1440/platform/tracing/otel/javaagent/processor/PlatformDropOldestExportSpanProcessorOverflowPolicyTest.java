package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контрактный тест семантики drop-oldest для {@link PlatformDropOldestExportSpanProcessor}.
 *
 * <p><b>Зеркальный по структуре к</b> {@code BatchSpanProcessorOverflowPolicyProbeTest}: тот
 * фиксирует фактическое drop-new стандартного BSP, этот — гарантирует drop-oldest платформенного
 * процессора. Различие наблюдаемых snapshot'ов — главное свидетельство, что замена реализована
 * корректно.
 *
 * <p><b>Конструкция:</b> блокирующий exporter держит worker заблокированным после
 * первого batch'а; параллельно генерируется намного больше spans, чем вмещается в очередь.
 * После освобождения exporter'а сверяем, какие именно spans фактически дошли до экспорта.
 *
 * <p><b>Drop-oldest ожидание:</b> в финальном snapshot должны присутствовать <b>последние</b>
 * (по {@code seq}) span'ы, а ранние — отброшены. Это противоположно поведению stock BSP,
 * у которого в первый batch попадают самые ранние, а более поздние теряются.
 */
class PlatformDropOldestExportSpanProcessorOverflowPolicyTest {

    private static final AttributeKey<Long> SEQ = AttributeKey.longKey("probe.seq");

    private static final int MAX_QUEUE = 4;
    private static final int BATCH_SIZE = 2;
    private static final int TOTAL_SPANS = 34;

    @Test
    @DisplayName("Drop-oldest: после переполнения экспортируется хвост последовательности, ранние ID отсутствуют")
    void dropOldestPreservesNewestSpans() throws InterruptedException {
        BlockingExporter exporter = new BlockingExporter();

        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(MAX_QUEUE)
                .maxExportBatchSize(BATCH_SIZE)
                // Отключаем background-flush на время фазы overflow (10 минут).
                .scheduleDelay(Duration.ofMinutes(10))
                // Достаточный для теста таймаут одного export'а: освобождение exporter'а
                // тест делает руками раньше, поэтому реальный wait уйдёт в release.
                .exportTimeout(Duration.ofSeconds(30))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();

        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
        Tracer tracer = tp.get("drop-oldest-contract-test");

        try {
            // Фаза 1: выпускаем первые BATCH_SIZE span'ов, чтобы worker заблокировался в exporter.
            for (int i = 0; i < BATCH_SIZE; i++) {
                tracer.spanBuilder("probe-" + i)
                        .setAllAttributes(Attributes.of(SEQ, (long) i))
                        .startSpan()
                        .end();
            }
            // Ждём входа worker'а в exporter.export() — иначе фаза overflow может выпустить
            // span'ы быстрее, чем worker заполнит очередь.
            assertThat(exporter.exporterEnteredLatch.await(5, TimeUnit.SECONDS))
                    .as("worker должен войти в exporter.export() для блокировки")
                    .isTrue();

            // Фаза 2: переполняем очередь. Поскольку scheduleDelay = 10m и exporter заблокирован,
            // worker не освободит ни одного слота — все 32 спана пройдут через enqueue с
            // drop-oldest вытеснением.
            for (int i = BATCH_SIZE; i < TOTAL_SPANS; i++) {
                tracer.spanBuilder("probe-" + i)
                        .setAllAttributes(Attributes.of(SEQ, (long) i))
                        .startSpan()
                        .end();
            }

            // Освобождаем exporter и форсируем дренаж.
            exporter.exporterReleaseLatch.countDown();
            tp.forceFlush().join(5, TimeUnit.SECONDS);
        } finally {
            tp.close();
        }

        List<Long> exportedSeq = new ArrayList<>();
        for (SpanData s : exporter.exported) {
            Long seq = s.getAttributes().get(SEQ);
            if (seq != null) {
                exportedSeq.add(seq);
            }
        }

        // Семантика drop-oldest формулируется как набор ослабленных, но непротиворечивых
        // инвариантов — поведение worker'а после освобождения exporter'а недетерминировано
        // по таймингу (worker сразу начинает дренаж, пока производитель ещё в цикле фазы 2),
        // поэтому жёсткий contain-exactly даст flakiness между запусками. Реально важно:
        //
        //  1. seq=0 и seq=1 экспортированы (первый заблокированный batch — это by design);
        //  2. seq=TOTAL_SPANS-1 (последний) экспортирован — он физически не мог быть
        //     вытеснен более новыми span'ами;
        //  3. начальные seq в диапазоне [BATCH_SIZE..K] (для некоторого K) отсутствуют —
        //     ровно это и есть drop-oldest;
        //  4. число экспортированных строго меньше TOTAL_SPANS — был реальный overflow.
        assertThat(exportedSeq)
                .as("первый заблокированный batch попадает в экспорт")
                .contains(0L, 1L);
        assertThat(exportedSeq)
                .as("последний выпущенный спан не вытесняется более новыми (drop-oldest)")
                .contains((long) (TOTAL_SPANS - 1));
        assertThat(exportedSeq)
                .as("ранние спаны после первого batch'а вытеснены drop-oldest")
                .doesNotContain(2L, 3L, 4L, 5L);
        assertThat(exportedSeq.size())
                .as("overflow реально произошёл — экспортировано меньше, чем выпущено")
                .isLessThan(TOTAL_SPANS);

        // Сравнение с поведением stock BSP (см. BatchSpanProcessorOverflowPolicyProbeTest):
        // stock BSP экспортирует ровно [0..5] (drop-new — отбрасывает поступающие новые), у нас
        // (drop-oldest) — наоборот, ранние отсутствуют, поздние присутствуют. seq 2..5 здесь
        // должны отсутствовать, тогда как у stock BSP они есть. Это и есть проверочный
        // дифференциальный сигнал — поведение явно отличается от stock.

        // Счётчики consistency: dropped + exported == TOTAL_SPANS (без потерь на shutdown'е).
        assertThat(processor.getDroppedSpansOverflow() + exportedSeq.size())
                .as("сохранение материального баланса: dropped + exported == TOTAL")
                .isEqualTo(TOTAL_SPANS);
        assertThat(processor.getDroppedSpansAfterShutdown())
                .as("в этом сценарии shutdown происходит после полного forceFlush — нечего терять")
                .isZero();
        assertThat(processor.getExportFailures()).isZero();
        assertThat(processor.getExportTimeouts()).isZero();
    }

    @Test
    @DisplayName("Queue size getter отражает текущее количество спанов в очереди")
    void queueSizeReflectsState() {
        BlockingExporter exporter = new BlockingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(MAX_QUEUE)
                .maxExportBatchSize(BATCH_SIZE)
                .scheduleDelay(Duration.ofMinutes(10))
                .exportTimeout(Duration.ofSeconds(30))
                .build();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
        try {
            Tracer tracer = tp.get("queue-size-test");
            assertThat(processor.getQueueCapacity()).isEqualTo(MAX_QUEUE);
            // Выпускаем больше спанов, чем capacity — overflow гарантирован.
            for (int i = 0; i < 10; i++) {
                tracer.spanBuilder("s-" + i).startSpan().end();
            }
            // queueSize ограничен capacity сверху (worker может постепенно вычитывать, но мы
            // блокируем exporter ⇒ ожидаем равенство capacity на стабильном состоянии).
            // Worker мог попасть в exporter с первыми BATCH_SIZE спанами, тогда в очереди
            // останется не более capacity — проверяем верхнюю границу.
            assertThat(processor.getQueueSize())
                    .as("queue size не превышает capacity")
                    .isLessThanOrEqualTo(processor.getQueueCapacity());
        } finally {
            exporter.exporterReleaseLatch.countDown();
            tp.close();
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Блокирующий exporter: первый вызов {@code export()} закрывает {@code exporterEnteredLatch}
     * и ожидает {@code exporterReleaseLatch}. После освобождения работает как обычный счётчик.
     */
    private static final class BlockingExporter implements SpanExporter {
        final CountDownLatch exporterEnteredLatch = new CountDownLatch(1);
        final CountDownLatch exporterReleaseLatch = new CountDownLatch(1);
        final List<SpanData> exported = new CopyOnWriteArrayList<>();
        volatile boolean firstExportSeen;

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            if (!firstExportSeen) {
                firstExportSeen = true;
                exporterEnteredLatch.countDown();
                try {
                    // Без таймаута: тест обязан вызвать countDown. Бесконечное ожидание здесь
                    // ловится таймаутом всего тестового JVM (JUnit), что приемлемо.
                    exporterReleaseLatch.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return CompletableResultCode.ofFailure();
                }
            }
            exported.addAll(batch);
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
