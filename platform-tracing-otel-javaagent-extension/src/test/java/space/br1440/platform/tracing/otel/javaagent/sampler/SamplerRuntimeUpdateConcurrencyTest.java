package space.br1440.platform.tracing.otel.javaagent.sampler;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Конкурентный контракт {@link SamplerStateHolder} (PR-E gap-check; ADR-runtime-sampling-policy,
 * инварианты C-4 atomic update / C-5 last-known-good / C-6 версионирование):
 * <ul>
 *   <li>параллельные валидные апдейты не теряются (CAS-retry), версия растёт строго монотонно;</li>
 *   <li>поток невалидных апдейтов посреди валидных никогда не публикуется и не рвёт LKG;</li>
 *   <li>читатели в любой момент видят только консистентные снимки (ratio из допустимого
 *       множества писателей, версия не убывает).</li>
 * </ul>
 */
class SamplerRuntimeUpdateConcurrencyTest {

    private static final int WRITER_THREADS = 4;
    private static final int UPDATES_PER_WRITER = 250;
    /** Ratio, которые публикуют валидные писатели; читатель не должен увидеть ничего вне множества. */
    private static final Set<Double> VALID_RATIOS = Set.of(0.5, 0.1, 0.2, 0.3, 0.4);

    @Test
    void конкурентные_валидные_апдейты_не_теряются_и_версия_строго_монотонна() throws Exception {
        SamplerStateHolder holder = new SamplerStateHolder(true, List.of(), List.of("on"), Map.of(), 0.5);
        long v0 = holder.version();

        runConcurrently(holder, /* poisonWriter= */ false);

        // Каждый tryUpdate с UnaryOperator (prev -> prev.version()+1) обязан примениться:
        // contention решается CAS-retry, а не потерей апдейта.
        assertThat(holder.version())
                .as("ни один валидный апдейт не потерян")
                .isEqualTo(v0 + (long) WRITER_THREADS * UPDATES_PER_WRITER);
    }

    @Test
    void невалидные_апдейты_посреди_валидных_не_публикуются_и_не_рвут_lkg() throws Exception {
        SamplerStateHolder holder = new SamplerStateHolder(true, List.of(), List.of("on"), Map.of(), 0.5);
        long v0 = holder.version();

        int rejected = runConcurrently(holder, /* poisonWriter= */ true);

        // Все невалидные отвергнуты, все валидные применены: версия выросла ровно на число валидных.
        assertThat(rejected).isEqualTo(UPDATES_PER_WRITER);
        assertThat(holder.version()).isEqualTo(v0 + (long) WRITER_THREADS * UPDATES_PER_WRITER);
        assertThat(VALID_RATIOS).contains(holder.current().defaultRatio());
    }

    /**
     * @return число отвергнутых (невалидных) апдейтов
     */
    private static int runConcurrently(SamplerStateHolder holder, boolean poisonWriter) throws Exception {
        int threads = WRITER_THREADS + (poisonWriter ? 1 : 0) + /* reader */ 1;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(WRITER_THREADS + (poisonWriter ? 1 : 0));
        AtomicBoolean stopReader = new AtomicBoolean(false);
        AtomicInteger rejectedCount = new AtomicInteger();
        AtomicReference<String> readerViolation = new AtomicReference<>();

        try {
            // Валидные писатели: каждый публикует свой ratio (распознаваемый читателем).
            List<Double> ratios = List.copyOf(VALID_RATIOS);
            for (int w = 0; w < WRITER_THREADS; w++) {
                double myRatio = ratios.get(w % ratios.size());
                pool.submit(() -> {
                    await(start);
                    try {
                        for (int i = 0; i < UPDATES_PER_WRITER; i++) {
                            boolean applied = holder.tryUpdate(prev -> new SamplerState(
                                    true, List.of(), Set.of("on"), Map.of(), myRatio,
                                    prev.version() + 1, Instant.now(), "concurrency-test"));
                            if (!applied) {
                                readerViolation.compareAndSet(null,
                                        "валидный апдейт отвергнут (ratio=" + myRatio + ")");
                            }
                        }
                    } finally {
                        writersDone.countDown();
                    }
                });
            }

            // Poison-писатель: невалидный ratio — конструктор SamplerState бросает IAE,
            // tryUpdate обязан вернуть false и не тронуть текущий снимок.
            if (poisonWriter) {
                pool.submit(() -> {
                    await(start);
                    try {
                        for (int i = 0; i < UPDATES_PER_WRITER; i++) {
                            boolean applied = holder.tryUpdate(prev -> new SamplerState(
                                    true, List.of(), Set.of("on"), Map.of(), 5.0,
                                    prev.version() + 1, Instant.now(), "poison"));
                            if (!applied) {
                                rejectedCount.incrementAndGet();
                            }
                        }
                    } finally {
                        writersDone.countDown();
                    }
                });
            }

            // Читатель: имитирует hot-path — непрерывно читает снимок и проверяет консистентность.
            pool.submit(() -> {
                await(start);
                long lastVersion = 0;
                while (!stopReader.get()) {
                    SamplerState state = holder.current();
                    double ratio = state.defaultRatio();
                    if (ratio != 0.5 && !VALID_RATIOS.contains(ratio)) {
                        readerViolation.compareAndSet(null,
                                "читатель увидел ratio вне множества писателей: " + ratio);
                    }
                    if (state.version() < lastVersion) {
                        readerViolation.compareAndSet(null,
                                "версия убыла: " + lastVersion + " -> " + state.version());
                    }
                    lastVersion = state.version();
                    if (state.policySnapshot() == null) {
                        readerViolation.compareAndSet(null,
                                "снимок без policy snapshot (version=" + state.version() + ")");
                    }
                }
            });

            start.countDown();
            assertThat(writersDone.await(30, TimeUnit.SECONDS))
                    .as("писатели должны завершиться за 30 с")
                    .isTrue();
        } finally {
            stopReader.set(true);
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(readerViolation.get())
                .as("нарушений консистентности быть не должно")
                .isNull();
        return rejectedCount.get();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
