package space.br1440.platform.tracing.otel.runtime.versioned;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт {@link VersionedStateHolder}: атомарная публикация, last-known-good на всех
 * ветках отказа (null/исключение builder'а/валидатора, отказ валидатора) и корректность под
 * конкуренцией (CAS-retry, монотонность версий, отсутствие потерянных апдейтов).
 */
@DisplayName("VersionedStateHolder: atomic publish + last-known-good + concurrency")
class VersionedStateHolderTest {

    private record Snapshot(long version, int value) implements VersionedState {
    }

    private static VersionedStateHolder<Snapshot> holder() {
        return new VersionedStateHolder<>(new Snapshot(0, 0));
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    @Test
    @DisplayName("null initial → IllegalArgumentException")
    void initialNullRejected() {
        assertThatThrownBy(() -> new VersionedStateHolder<>(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("initial snapshot must not be null");
    }

    @Test
    @DisplayName("builder бросил VirtualMachineError → пробрасывается, LKG сохранён")
    void builderFatalErrorRethrown() {
        VersionedStateHolder<Snapshot> holder = holder();
        OutOfMemoryError fatal = new OutOfMemoryError("simulated");
        assertThatThrownBy(() -> holder.tryUpdate(prev -> {
            throw fatal;
        })).isSameAs(fatal);
        assertThat(holder.current().value()).isZero();
    }

    @Test
    @DisplayName("validator бросил LinkageError → пробрасывается, LKG сохранён")
    void validatorFatalErrorRethrown() {
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));
        LinkageError fatal = new LinkageError("simulated");
        assertThatThrownBy(() -> holder.tryUpdate(
                prev -> new Snapshot(2, 8),
                s -> {
                    throw fatal;
                })).isSameAs(fatal);
        assertThat(holder.current().value()).isEqualTo(7);
    }

    @Test
    @DisplayName("builder бросил InterruptedException → interrupt flag, false, LKG")
    void builderInterruptedSetsFlagReturnsFalse() {
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));
        Thread.interrupted();

        boolean applied = holder.tryUpdate(prev -> sneakyThrow(new InterruptedException("interrupted")));

        assertThat(applied).isFalse();
        assertThat(Thread.interrupted()).isTrue();
        assertThat(holder.current().value()).isEqualTo(7);
    }

    @Test
    @DisplayName("validator бросил InterruptedException → interrupt flag, false, LKG")
    void validatorInterruptedSetsFlagReturnsFalse() {
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));
        Thread.interrupted();

        boolean applied = holder.tryUpdate(
                prev -> new Snapshot(2, 8),
                s -> sneakyThrow(new InterruptedException("interrupted")));

        assertThat(applied).isFalse();
        assertThat(Thread.interrupted()).isTrue();
        assertThat(holder.current().value()).isEqualTo(7);
    }

    @Test
    @DisplayName("version() делегирует current().version()")
    void versionDelegatesToCurrent() {
        VersionedStateHolder<Snapshot> holder = new VersionedStateHolder<>(new Snapshot(42, 0));
        assertThat(holder.version()).isEqualTo(42);
        holder.tryUpdate(prev -> new Snapshot(43, 1));
        assertThat(holder.version()).isEqualTo(43);
    }

    @Test
    @DisplayName("tryUpdate(builder) эквивалентен tryUpdate(builder, null)")
    void singleArgOverloadEquivalentToNullValidator() {
        VersionedStateHolder<Snapshot> singleArg = holder();
        VersionedStateHolder<Snapshot> nullValidator = holder();

        assertThat(singleArg.tryUpdate(prev -> new Snapshot(1, 1))).isTrue();
        assertThat(nullValidator.tryUpdate(prev -> new Snapshot(1, 1), null)).isTrue();

        assertThat(singleArg.tryUpdate(prev -> null)).isFalse();
        assertThat(nullValidator.tryUpdate(prev -> null, null)).isFalse();
        assertThat(singleArg.current()).isEqualTo(nullValidator.current());
    }

    @Test
    @DisplayName("успешный tryUpdate публикует новый снимок и поднимает версию")
    void successfulUpdatePublishes() {
        VersionedStateHolder<Snapshot> holder = holder();

        boolean applied = holder.tryUpdate(prev -> new Snapshot(prev.version() + 1, 42));

        assertThat(applied).isTrue();
        assertThat(holder.version()).isEqualTo(1);
        assertThat(holder.current().value()).isEqualTo(42);
    }

    @Test
    @DisplayName("builder вернул null → keep last-known-good")
    void nullCandidateKeepsLkg() {
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));

        boolean applied = holder.tryUpdate(prev -> null);

        assertThat(applied).isFalse();
        assertThat(holder.current().value()).isEqualTo(7);
        assertThat(holder.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("builder бросил исключение → keep last-known-good, наружу не пробрасывается")
    void builderThrowKeepsLkg() {
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));

        boolean applied = holder.tryUpdate(prev -> {
            throw new IllegalStateException("bad input");
        });

        assertThat(applied).isFalse();
        assertThat(holder.current().value()).isEqualTo(7);
    }

    @Test
    @DisplayName("validator отверг кандидата → keep last-known-good")
    void validatorRejectKeepsLkg() {
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));

        boolean applied = holder.tryUpdate(prev -> new Snapshot(2, 999), s -> s.value() <= 100);

        assertThat(applied).isFalse();
        assertThat(holder.current().value()).isEqualTo(7);
    }

    @Test
    @DisplayName("validator бросил исключение → keep last-known-good")
    void validatorThrowKeepsLkg() {
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));

        boolean applied = holder.tryUpdate(prev -> new Snapshot(2, 8), s -> {
            throw new RuntimeException("validator boom");
        });

        assertThat(applied).isFalse();
        assertThat(holder.current().value()).isEqualTo(7);
    }

    @Test
    @DisplayName("null builder → false без изменений")
    void nullBuilderReturnsFalse() {
        VersionedStateHolder<Snapshot> holder = holder();
        assertThat(holder.tryUpdate(null)).isFalse();
        assertThat(holder.version()).isZero();
    }

    @Test
    @DisplayName("конкурентные апдейты: все применяются, версии монотонны, без потерь (CAS-retry)")
    void concurrentUpdatesAreSerializedWithoutLoss() throws InterruptedException {
        VersionedStateHolder<Snapshot> holder = holder();
        int threads = 8;
        int perThread = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger appliedCount = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    if (holder.tryUpdate(prev -> new Snapshot(prev.version() + 1, prev.value() + 1))) {
                        appliedCount.incrementAndGet();
                    }
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        int expected = threads * perThread;
        assertThat(appliedCount.get()).isEqualTo(expected);
        assertThat(holder.current().value()).isEqualTo(expected);
        assertThat(holder.version()).isEqualTo(expected);
    }
}
