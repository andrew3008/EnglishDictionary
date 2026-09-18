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

class OwnerRegistrationSlotTest {

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
        int threads = 16;
        AtomicLong ownerCounter = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Long>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> slot.replace(ownerCounter.incrementAndGet()).generation()));
            }
            // when
            List<Long> observedGenerations = new java.util.ArrayList<>();
            for (Future<Long> future : futures) {
                observedGenerations.add(future.get(5, TimeUnit.SECONDS));
            }

            // then
            Set<Long> distinct = observedGenerations.stream().collect(Collectors.toSet());
            assertThat(distinct).hasSize(threads);
            assertThat(distinct).containsExactlyInAnyOrderElementsOf(
                    java.util.stream.LongStream.rangeClosed(1, threads).boxed().toList());

            assertThat(slot.capture().generation()).isEqualTo(threads);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void captureNeverObservesTornPairOfOwnerAndGeneration() throws Exception {
        // given
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
                        return false;
                    }
                    lastSeenGeneration = state.generation();
                    if (state instanceof OwnerRegistrationState.Present<Long> present) {
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
            // when/then
            assertThat(readerFuture.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }
    }
}
