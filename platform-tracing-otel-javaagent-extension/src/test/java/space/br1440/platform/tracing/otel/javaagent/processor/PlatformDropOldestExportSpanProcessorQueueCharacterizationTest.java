package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SP-05 — Queue full / drop-oldest characterization.
 * <p>
 * Дополнительные тесты к уже существующим
 * {@link PlatformDropOldestExportSpanProcessorOverflowPolicyTest} (полный drop-oldest контракт)
 * и {@link PlatformDropOldestExportSpanProcessorLifecycleTest} (lifecycle/counters).
 * <p>
 * Фокус на:
 * <ul>
 *   <li>минимальный детерминированный proof drop-oldest с identity спанов;</li>
 *   <li>non-blocking {@code onEnd} — latch-based, без таймингов;</li>
 *   <li>изолированная проверка счётчика {@code droppedSpansOverflow};</li>
 *   <li>экспорт принятых спанов после снятия очередной нагрузки;</li>
 *   <li>bounded завершение shutdown после сценария переполнения.</li>
 * </ul>
 *
 * <b>Ключевая гарантия non-blocking:</b> {@code exporter.export()} вызывается worker'ом
 * <em>вне</em> {@code queueLock}, поэтому {@code onEnd} → {@code enqueueWithDropOldest}
 * всегда может захватить lock немедленно, даже если exporter заблокирован.
 */
class PlatformDropOldestExportSpanProcessorQueueCharacterizationTest {

    // -------------------------------------------------------------------------
    // SP-05 / Test 1
    // -------------------------------------------------------------------------

    /**
     * SP-05 / Test 1.
     * Минимальный identity-proof drop-oldest: при переполнении вытесняется именно старый спан,
     * а не новый. Спан-0 уходит в первый batch (до блокировки), спан-1 ставится в очередь,
     * затем спан-2 вытесняет спан-1 (oldest) — очередь содержит только спан-2.
     * После снятия блокировки: span-0 и span-2 экспортированы, span-1 — нет.
     */
    @Test
    @DisplayName("SP-05 / Test 1: при overflow вытесняется oldest span; newest принимается в очередь")
    void dropsOldestPendingSpanWhenQueueIsFull() throws InterruptedException {
        BlockingExporter exporter = new BlockingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(1)
                .maxExportBatchSize(1)
                .scheduleDelay(Duration.ofMinutes(10))
                .exportTimeout(Duration.ofSeconds(10))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
        Tracer tracer = tp.get("sp05-identity");

        try {
            // Фаза 1: span-0 → queue=[span-0], size(1)>=maxExportBatchSize(1) → signal.
            // Worker просыпается, дренирует [span-0], уходит в exporter.export() (блокируется).
            tracer.spanBuilder("span-0").startSpan().end();
            assertThat(exporter.exporterEnteredLatch.await(5, TimeUnit.SECONDS))
                    .as("worker должен войти в export() и заблокироваться").isTrue();

            // Фаза 2: queue пуста (worker дренировал span-0).
            // span-1 → queue=[span-1] (at capacity: size 1 == maxQueueSize 1).
            tracer.spanBuilder("span-1").startSpan().end();

            // Фаза 3: span-2 → queue full → pollFirst(span-1), droppedSpansOverflow++, queue=[span-2].
            // span.end() → onEnd() → enqueueWithDropOldest() — всё в calling thread, синхронно.
            tracer.spanBuilder("span-2").startSpan().end();

            // Детерминированная проверка до снятия блокировки (счётчик уже обновлён).
            assertThat(processor.getDroppedSpansOverflow())
                    .as("span-1 вытеснен как oldest: droppedSpansOverflow должен быть 1")
                    .isEqualTo(1);

        } finally {
            exporter.exporterReleaseLatch.countDown();
            processor.forceFlush().join(5, TimeUnit.SECONDS);
            tp.close();
        }

        List<String> names = exporter.exported.stream().map(SpanData::getName).toList();
        assertThat(names).as("span-0 экспортирован (первый batch до блокировки)")
                .contains("span-0");
        assertThat(names).as("span-2 экспортирован (newest после overflow)")
                .contains("span-2");
        assertThat(names).as("span-1 вытеснен drop-oldest — в экспорте его нет")
                .doesNotContain("span-1");
    }

    // -------------------------------------------------------------------------
    // SP-05 / Test 2
    // -------------------------------------------------------------------------

    /**
     * SP-05 / Test 2.
     * {@code onEnd} не блокирует calling thread даже когда очередь полна и exporter заблокирован.
     * <p>
     * Доказательство детерминировано через {@link CountDownLatch}: если {@code onEnd}
     * вернётся до таймаута — latch защёлкнется. Безопасность обеспечена тем, что
     * worker удерживает только {@code queueLock} в критической секции, а {@code exporter.export()}
     * вызывается <em>вне</em> lock'а.
     */
    @Test
    @DisplayName("SP-05 / Test 2: onEnd не блокирует caller, даже если exporter заблокирован и очередь полна")
    void onEndDoesNotBlockWhenQueueIsFull() throws InterruptedException {
        BlockingExporter exporter = new BlockingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(1)
                .maxExportBatchSize(1)
                .scheduleDelay(Duration.ofMinutes(10))
                .exportTimeout(Duration.ofSeconds(10))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
        Tracer tracer = tp.get("sp05-nonblocking");

        try {
            // Блокируем worker
            tracer.spanBuilder("fill-0").startSpan().end();
            assertThat(exporter.exporterEnteredLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // Заполняем очередь до capacity (worker заблокирован, не дренирует)
            tracer.spanBuilder("fill-1").startSpan().end();

            // Запускаем onEnd в отдельном потоке; ожидаем возврата через latch
            CountDownLatch onEndCompleted = new CountDownLatch(1);
            Thread caller = new Thread(() -> {
                tracer.spanBuilder("probe-non-blocking").startSpan().end();
                onEndCompleted.countDown();
            }, "sp05-onend-caller");
            caller.start();

            // Если onEnd блокирует — latch не защёлкнется за 2 секунды
            assertThat(onEndCompleted.await(2, TimeUnit.SECONDS))
                    .as("onEnd обязан вернуться немедленно: worker не держит queueLock во время export()")
                    .isTrue();
            caller.join(2_000);

        } finally {
            exporter.exporterReleaseLatch.countDown();
            tp.close();
        }
    }

    // -------------------------------------------------------------------------
    // SP-05 / Test 3
    // -------------------------------------------------------------------------

    /**
     * SP-05 / Test 3.
     * Счётчик {@link PlatformDropOldestExportSpanProcessor#getDroppedSpansOverflow()}
     * инкрементируется ровно по одному на каждый вытесненный спан.
     * <p>
     * Доказательство детерминировано: worker заблокирован в exporter, producer переполняет
     * очередь из calling thread — все операции синхронны, к моменту assert счётчик уже обновлён.
     */
    @Test
    @DisplayName("SP-05 / Test 3: droppedSpansOverflow инкрементируется ровно N раз при N overflow'ах")
    void incrementsDroppedSpanCounterWhenQueueIsFull() throws InterruptedException {
        BlockingExporter exporter = new BlockingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(2)
                .maxExportBatchSize(2)
                .scheduleDelay(Duration.ofMinutes(10))
                .exportTimeout(Duration.ofSeconds(10))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
        Tracer tracer = tp.get("sp05-counter");

        try {
            // Заполняем первый batch (size==maxExportBatchSize=2) → worker дренирует, блокируется
            tracer.spanBuilder("b-0").startSpan().end();
            tracer.spanBuilder("b-1").startSpan().end();
            assertThat(exporter.exporterEnteredLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // Очередь пуста. Заполняем до capacity:
            tracer.spanBuilder("q-0").startSpan().end(); // queue=[q-0]
            tracer.spanBuilder("q-1").startSpan().end(); // queue=[q-0, q-1] ← capacity

            // Каждый последующий спан вытесняет oldest:
            tracer.spanBuilder("q-2").startSpan().end(); // drop q-0 → droppedSpansOverflow=1, queue=[q-1, q-2]
            tracer.spanBuilder("q-3").startSpan().end(); // drop q-1 → droppedSpansOverflow=2, queue=[q-2, q-3]

            // Все вызовы span.end() — синхронные, счётчик уже обновлён к этому моменту
            assertThat(processor.getDroppedSpansOverflow())
                    .as("два overflow → droppedSpansOverflow == 2")
                    .isEqualTo(2);
            assertThat(processor.getQueueSize())
                    .as("очередь содержит capacity=2 спана")
                    .isEqualTo(2);
            assertThat(processor.getQueueCapacity()).isEqualTo(2);

        } finally {
            exporter.exporterReleaseLatch.countDown();
            tp.close();
        }
    }

    // -------------------------------------------------------------------------
    // SP-05 / Test 4
    // -------------------------------------------------------------------------

    /**
     * SP-05 / Test 4.
     * После снятия queue-нагрузки принятые (newest) спаны доходят до exporter'а.
     * Вытесненные спаны в экспорте отсутствуют.
     */
    @Test
    @DisplayName("SP-05 / Test 4: принятые спаны экспортируются после снятия очередной нагрузки")
    void exportsAcceptedSpansAfterQueuePressureIsReleased() throws InterruptedException {
        BlockingExporter exporter = new BlockingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(2)
                .maxExportBatchSize(2)
                .scheduleDelay(Duration.ofMinutes(10))
                .exportTimeout(Duration.ofSeconds(10))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
        Tracer tracer = tp.get("sp05-post-pressure");

        try {
            // Блокируем worker первым batch'ем
            tracer.spanBuilder("first-0").startSpan().end();
            tracer.spanBuilder("first-1").startSpan().end();
            assertThat(exporter.exporterEnteredLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // Заполняем очередь, затем переполняем
            tracer.spanBuilder("queued-0").startSpan().end(); // queue=[queued-0]
            tracer.spanBuilder("queued-1").startSpan().end(); // queue=[queued-0, queued-1] ← capacity
            tracer.spanBuilder("queued-2").startSpan().end(); // drop queued-0, queue=[queued-1, queued-2]

            assertThat(processor.getDroppedSpansOverflow())
                    .as("один overflow произошёл перед снятием нагрузки")
                    .isEqualTo(1);

            // Снимаем нагрузку и дренируем
            exporter.exporterReleaseLatch.countDown();
            CompletableResultCode flush = processor.forceFlush();
            flush.join(5, TimeUnit.SECONDS);
            assertThat(flush.isSuccess()).isTrue();

        } finally {
            exporter.exporterReleaseLatch.countDown(); // defensive release
            tp.close();
        }

        List<String> names = exporter.exported.stream().map(SpanData::getName).toList();
        assertThat(names)
                .as("первый batch доходит до exporter (был в обработке в момент блокировки)")
                .contains("first-0", "first-1");
        assertThat(names)
                .as("queued-1 и queued-2 — survivors очереди после overflow")
                .contains("queued-1", "queued-2");
        assertThat(names)
                .as("queued-0 был oldest при overflow — не должен быть экспортирован")
                .doesNotContain("queued-0");
    }

    // -------------------------------------------------------------------------
    // SP-05 / Test 5
    // -------------------------------------------------------------------------

    /**
     * SP-05 / Test 5.
     * {@link PlatformDropOldestExportSpanProcessor#shutdown()} возвращает не-null
     * {@link CompletableResultCode} и завершается после сценария с переполнением очереди.
     * Счётчик {@code droppedSpansOverflow} отражает реальные потери.
     */
    @Test
    @DisplayName("SP-05 / Test 5: shutdown завершается после сценария переполнения очереди")
    void shutdownCompletesAfterQueuePressureScenario() throws InterruptedException {
        BlockingExporter exporter = new BlockingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(2)
                .maxExportBatchSize(2)
                .scheduleDelay(Duration.ofMinutes(10))
                .exportTimeout(Duration.ofSeconds(5))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();

        try {
            Tracer tracer = tp.get("sp05-shutdown");

            // Блокируем worker
            tracer.spanBuilder("s-0").startSpan().end();
            tracer.spanBuilder("s-1").startSpan().end();
            assertThat(exporter.exporterEnteredLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // Переполняем очередь
            tracer.spanBuilder("s-2").startSpan().end(); // queue=[s-2]
            tracer.spanBuilder("s-3").startSpan().end(); // queue=[s-2, s-3]
            tracer.spanBuilder("s-4").startSpan().end(); // overflow: drop s-2, queue=[s-3, s-4]

            assertThat(processor.getDroppedSpansOverflow()).isEqualTo(1);

            // Снимаем нагрузку до shutdown
            exporter.exporterReleaseLatch.countDown();

        } finally {
            // tp.close() вызывает processor.shutdown()
            tp.close();
        }

        // shutdown() идемпотентен — возвращает тот же shutdownResult
        CompletableResultCode sd = processor.shutdown();
        assertThat(sd).isNotNull();
        sd.join(5, TimeUnit.SECONDS);
        assertThat(sd.isDone())
                .as("shutdown должен завершиться после снятия нагрузки с exporter'а")
                .isTrue();
        assertThat(processor.getDroppedSpansOverflow())
                .as("один overflow был зафиксирован и остаётся актуальным после shutdown")
                .isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    /**
     * Блокирующий exporter: первый вызов {@code export()} фиксирует вход worker'а через
     * {@code exporterEnteredLatch} и блокируется до {@code exporterReleaseLatch}. Все
     * последующие вызовы работают как счётный accumulator.
     * <p>
     * Структура аналогична {@code BlockingExporter} из
     * {@link PlatformDropOldestExportSpanProcessorOverflowPolicyTest}.
     */
    private static final class BlockingExporter implements SpanExporter {
        final CountDownLatch exporterEnteredLatch = new CountDownLatch(1);
        final CountDownLatch exporterReleaseLatch = new CountDownLatch(1);
        final List<SpanData> exported = new CopyOnWriteArrayList<>();
        private volatile boolean firstExportSeen;

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            if (!firstExportSeen) {
                firstExportSeen = true;
                exporterEnteredLatch.countDown();
                try {
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
