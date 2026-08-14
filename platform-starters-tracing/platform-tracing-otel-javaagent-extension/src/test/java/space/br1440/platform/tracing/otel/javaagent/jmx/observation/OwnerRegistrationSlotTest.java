package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Покрывает адверсариально найденный блокер: раздельная аллокация generation и публикация
 * владельца допускала reverse publication (опубликованная generation уменьшалась). Здесь
 * generation выдаётся внутри той же атомарной операции, что публикует владельца.
 */
class OwnerRegistrationSlotTest {

    @Test
    void initialStateIsAbsentWithZeroGeneration() {
        OwnerRegistrationSlot<String> slot = new OwnerRegistrationSlot<>();

        OwnerRegistrationState<String> state = slot.capture();

        assertThat(state).isInstanceOf(OwnerRegistrationState.Absent.class);
        assertThat(state.generation()).isZero();
    }

    @Test
    void replaceProducesStrictlyIncreasingGeneration() {
        OwnerRegistrationSlot<String> slot = new OwnerRegistrationSlot<>();

        OwnerRegistrationState<String> first = slot.replace("owner-1");
        OwnerRegistrationState<String> second = slot.replace("owner-2");

        assertThat(first.generation()).isEqualTo(1L);
        assertThat(second.generation()).isEqualTo(2L);
        assertThat(((OwnerRegistrationState.Present<String>) second).owner()).isEqualTo("owner-2");
    }

    @Test
    void removePreservesGenerationForStrictlyIncreasingReattachment() {
        OwnerRegistrationSlot<String> slot = new OwnerRegistrationSlot<>();
        slot.replace("owner-1");

        OwnerRegistrationState<String> absent = slot.remove();
        assertThat(absent).isInstanceOf(OwnerRegistrationState.Absent.class);
        assertThat(absent.generation()).isEqualTo(1L);

        OwnerRegistrationState<String> reattached = slot.replace("owner-2");
        assertThat(reattached.generation()).isEqualTo(2L);
    }

    @Test
    void concurrentReplacementsNeverPublishDecreasingGenerationAndEachGetsUniqueGeneration() throws Exception {
        OwnerRegistrationSlot<Long> slot = new OwnerRegistrationSlot<>();
        int threads = 16;
        AtomicLong ownerCounter = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Long>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> slot.replace(ownerCounter.incrementAndGet()).generation()));
            }
            List<Long> observedGenerations = new java.util.ArrayList<>();
            for (Future<Long> future : futures) {
                observedGenerations.add(future.get(5, TimeUnit.SECONDS));
            }

            // Каждая успешная замена получила уникальную generation — не потеряно и не задвоено.
            Set<Long> distinct = observedGenerations.stream().collect(Collectors.toSet());
            assertThat(distinct).hasSize(threads);
            assertThat(distinct).containsExactlyInAnyOrderElementsOf(
                    java.util.stream.LongStream.rangeClosed(1, threads).boxed().toList());

            // Reverse publication невозможен: финальная generation равна максимальной выданной.
            assertThat(slot.capture().generation()).isEqualTo(threads);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void captureNeverObservesTornPairOfOwnerAndGeneration() throws Exception {
        OwnerRegistrationSlot<Long> slot = new OwnerRegistrationSlot<>();
        int writers = 8;
        int iterationsPerWriter = 500;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        AtomicLong ownerSequence = new AtomicLong();
        try {
            List<Future<Void>> writerFutures = new java.util.ArrayList<>();
            for (int i = 0; i < writers; i++) {
                writerFutures.add(pool.submit(() -> {
                    for (int j = 0; j < iterationsPerWriter; j++) {
                        slot.replace(ownerSequence.incrementAndGet());
                    }
                    return null;
                }));
            }
            Future<Boolean> readerFuture = pool.submit(() -> {
                long lastSeenGeneration = -1;
                for (int i = 0; i < 5000; i++) {
                    OwnerRegistrationState<Long> state = slot.capture();
                    if (state.generation() < lastSeenGeneration) {
                        return false; // наблюдали убывание — reverse publication
                    }
                    lastSeenGeneration = state.generation();
                    if (state instanceof OwnerRegistrationState.Present<Long> present) {
                        // owner и generation должны быть парой, зафиксированной одной атомарной операцией:
                        // owner ненулевой и generation положительна одновременно.
                        if (present.owner() == null || present.generation() <= 0) {
                            return false;
                        }
                    }
                }
                return true;
            });

            for (Future<Void> future : writerFutures) {
                future.get(10, TimeUnit.SECONDS);
            }
            assertThat(readerFuture.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }
    }
}
