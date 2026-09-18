package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

/**
 * Проверяет инвариант {@link OwnerRegistrationSlot}: generation монотонно возрастает,
 * присвоение владельца и его generation публикуются атомарно (без "torn read"),
 * и это остаётся верным под конкурентной нагрузкой на replace()/capture().
 */
class OwnerRegistrationSlotTest {

    private static final int CONCURRENT_WRITER_THREADS = 16;
    private static final int TORN_READ_WRITER_THREADS = 8;
    private static final int TORN_READ_ITERATIONS_PER_WRITER = 500;
    private static final int TORN_READ_READER_SAMPLES = 5_000;

    private static final Duration FUTURE_GET_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void initialStateIsAbsentWithZeroGeneration() {
        // given
        OwnerRegistrationSlot<String> slot = new OwnerRegistrationSlot<>();

        // when
        OwnerRegistrationState<String> state = slot.capture();

        // then
        assertThat(state).isInstanceOf(OwnerRegistrationState.Absent.class);
        assertThat(state.generation()).isZero();
    }

    @Test
    void replaceProducesStrictlyIncreasingGeneration() {
        // given
        OwnerRegistrationSlot<String> slot = new OwnerRegistrationSlot<>();

        // when
        OwnerRegistrationState<String> first = slot.replace("owner-1");
        OwnerRegistrationState<String> second = slot.replace("owner-2");

        // then
        assertThat(first.generation()).isEqualTo(1L);
        assertThat(second.generation()).isEqualTo(2L);
        assertThat(((OwnerRegistrationState.Present<String>) second).owner()).isEqualTo("owner-2");
    }

    @Test
    void removePreservesGenerationForStrictlyIncreasingReattachment() {
        // given
        OwnerRegistrationSlot<String> slot = new OwnerRegistrationSlot<>();
        slot.replace("owner-1");

        // when
        OwnerRegistrationState<String> absent = slot.remove();
        OwnerRegistrationState<String> reattached = slot.replace("owner-2");

        // then
        assertThat(absent).isInstanceOf(OwnerRegistrationState.Absent.class);
        assertThat(absent.generation()).isEqualTo(1L);
        assertThat(reattached.generation()).isEqualTo(2L);
    }

    @Test
    void concurrentReplacementsNeverPublishDecreasingGenerationAndEachGetsUniqueGeneration() throws Exception {
        // given
        OwnerRegistrationSlot<Long> slot = new OwnerRegistrationSlot<>();
        AtomicLong ownerCounter = new AtomicLong();

        // when
        List<Long> observedGenerations;
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_WRITER_THREADS)) {
            List<Future<Long>> futures = submitReplaceTasks(pool, slot, ownerCounter, CONCURRENT_WRITER_THREADS);
            observedGenerations = awaitAll(futures, FUTURE_GET_TIMEOUT);
        }

        // then
        Set<Long> distinctGenerations = new HashSet<>(observedGenerations);
        assertThat(distinctGenerations).hasSize(CONCURRENT_WRITER_THREADS);
        assertThat(distinctGenerations).containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(1, CONCURRENT_WRITER_THREADS)
                        .boxed()
                        .toList()
        );
        assertThat(slot.capture().generation()).isEqualTo(CONCURRENT_WRITER_THREADS);
    }

    @Test
    void captureNeverObservesTornPairOfOwnerAndGeneration() throws Exception {
        // given
        OwnerRegistrationSlot<Long> slot = new OwnerRegistrationSlot<>();
        AtomicLong ownerSequence = new AtomicLong();

        // when / then
        try (ExecutorService pool = Executors.newFixedThreadPool(TORN_READ_WRITER_THREADS + 1)) {
            List<Future<Void>> writerFutures = submitContinuousWriters(
                    pool, slot, ownerSequence, TORN_READ_WRITER_THREADS, TORN_READ_ITERATIONS_PER_WRITER);
            Future<Boolean> readerFuture = pool.submit(() -> readerObservesNoTornState(slot, TORN_READ_READER_SAMPLES));

            awaitAll(writerFutures, FUTURE_GET_TIMEOUT);
            assertThat(readerFuture.get(FUTURE_GET_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        }
    }

    private static List<Future<Long>> submitReplaceTasks(ExecutorService pool,
                                                         OwnerRegistrationSlot<Long> slot,
                                                         AtomicLong ownerCounter,
                                                         int taskCount) {
        List<Future<Long>> futures = new ArrayList<>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            futures.add(pool.submit(() -> slot.replace(ownerCounter.incrementAndGet()).generation()));
        }
        return futures;
    }

    private static List<Future<Void>> submitContinuousWriters(ExecutorService pool,
                                                              OwnerRegistrationSlot<Long> slot,
                                                              AtomicLong ownerSequence,
                                                              int writerCount,
                                                              int iterationsPerWriter) {
        List<Future<Void>> futures = new ArrayList<>(writerCount);
        for (int i = 0; i < writerCount; i++) {
            futures.add(pool.submit(() -> {
                for (int j = 0; j < iterationsPerWriter; j++) {
                    slot.replace(ownerSequence.incrementAndGet());
                }
                return null;
            }));
        }
        return futures;
    }

    private static boolean readerObservesNoTornState(OwnerRegistrationSlot<Long> slot, int samples) {
        long lastSeenGeneration = -1L;
        for (int i = 0; i < samples; i++) {
            OwnerRegistrationState<Long> state = slot.capture();
            if (state.generation() < lastSeenGeneration) {
                return false;
            }
            lastSeenGeneration = state.generation();

            if (state instanceof OwnerRegistrationState.Present<Long>(Long owner, long generation)
                    && (owner == null || generation <= 0)) {
                return false;
            }
        }
        return true;
    }

    private static <T> List<T> awaitAll(List<Future<T>> futures, Duration timeout) throws Exception {
        List<T> results = new ArrayList<>(futures.size());
        for (Future<T> future : futures) {
            results.add(future.get(timeout.toSeconds(), TimeUnit.SECONDS));
        }
        return results;
    }
}
