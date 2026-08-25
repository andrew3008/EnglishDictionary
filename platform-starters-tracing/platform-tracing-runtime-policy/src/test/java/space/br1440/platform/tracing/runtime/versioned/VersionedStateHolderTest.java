package space.br1440.platform.tracing.runtime.versioned;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт {@link VersionedStateHolder}: атомарная публикация, last-known-good на всех
 * ветках отказа (null-кандидат / исключение builder'а или валидатора, отказ валидатора)
 * и корректность под конкуренцией (CAS-retry, монотонность версий, отсутствие потерянных апдейтов).
 * Null {@code builder} — программная ошибка: {@link NullPointerException}.
 */
@DisplayName("VersionedStateHolder: atomic publish + last-known-good + concurrency")
class VersionedStateHolderTest {

    private record Snapshot(long version, int value) implements VersionedState {
    }

    private static VersionedStateHolder<Snapshot> holder() {
        return holder(new Snapshot(0, 0));
    }

    private static VersionedStateHolder<Snapshot> holder(@Nullable Snapshot initial) {
        return new VersionedStateHolder<>(initial);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void clearInterruptStatus() {
        Thread.interrupted();
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    @Test
    @DisplayName("null initial → IllegalArgumentException")
    void initialNullRejected() {
        // when
        // then
        assertThatThrownBy(() -> holder(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("initial snapshot must not be null");
    }

    @Test
    @DisplayName("builder threw VirtualMachineError → rethrown, LKG preserved")
    void builderFatalErrorRethrown() {
        // given
        VersionedStateHolder<Snapshot> holder = holder();
        OutOfMemoryError fatal = new OutOfMemoryError("simulated");

        // when
        // then
        assertThatThrownBy(() -> holder.tryUpdate(prev -> {
            throw fatal;
        })).isSameAs(fatal);
        assertThat(holder.current().value()).isZero();
    }

    @Test
    @DisplayName("validator threw LinkageError → rethrown, LKG preserved")
    void validatorFatalErrorRethrown() {
        // given
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));
        LinkageError fatal = new LinkageError("simulated");

        // when
        // then
        assertThatThrownBy(() -> holder.tryUpdate(
                prev -> new Snapshot(2, 8),
                s -> {
                    throw fatal;
                })).isSameAs(fatal);
        assertThat(holder.current().value()).isEqualTo(7);
    }

    @Test
    @DisplayName("builder threw InterruptedException → interrupt flag, false, LKG")
    void builderInterruptedSetsFlagReturnsFalse() {
        // given
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));
        clearInterruptStatus();

        // when
        boolean applied = holder.tryUpdate(prev -> sneakyThrow(new InterruptedException("interrupted")));

        // then
        assertThat(applied).isFalse();
        assertThat(Thread.interrupted()).isTrue();
        assertThat(holder.current().value()).isEqualTo(7);
    }

    @Test
    @DisplayName("validator threw InterruptedException → interrupt flag, false, LKG")
    void validatorInterruptedSetsFlagReturnsFalse() {
        // given
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));
        clearInterruptStatus();

        // when
        boolean applied = holder.tryUpdate(
                prev -> new Snapshot(2, 8),
                s -> sneakyThrow(new InterruptedException("interrupted")));

        // then
        assertThat(applied).isFalse();
        assertThat(Thread.interrupted()).isTrue();
        assertThat(holder.current().value()).isEqualTo(7);
    }

    @Test
    @DisplayName("version() delegates to current().version()")
    void versionDelegatesToCurrent() {
        // given
        VersionedStateHolder<Snapshot> holder = new VersionedStateHolder<>(new Snapshot(42, 0));

        // when
        // then
        assertThat(holder.version()).isEqualTo(42);
        holder.tryUpdate(prev -> new Snapshot(43, 1));
        assertThat(holder.version()).isEqualTo(43);
    }

    @Test
    @DisplayName("tryUpdate(builder) is equivalent to tryUpdate(builder, null)")
    void singleArgOverloadEquivalentToNullValidator() {
        // given
        VersionedStateHolder<Snapshot> singleArg = holder();
        VersionedStateHolder<Snapshot> nullValidator = holder();

        // when
        // then
        assertThat(singleArg.tryUpdate(prev -> new Snapshot(1, 1))).isTrue();
        assertThat(nullValidator.tryUpdate(prev -> new Snapshot(1, 1), null)).isTrue();

        assertThat(singleArg.tryUpdate(prev -> null)).isFalse();
        assertThat(nullValidator.tryUpdate(prev -> null, null)).isFalse();
        assertThat(singleArg.current()).isEqualTo(nullValidator.current());
    }

    @Test
    @DisplayName("successful tryUpdate publishes new snapshot and bumps version")
    void successfulUpdatePublishes() {
        // given
        VersionedStateHolder<Snapshot> holder = holder();

        // when
        boolean applied = holder.tryUpdate(prev -> new Snapshot(prev.version() + 1, 42));

        // then
        assertThat(applied).isTrue();
        assertThat(holder.version()).isEqualTo(1);
        assertThat(holder.current().value()).isEqualTo(42);
    }

    @Test
    @DisplayName("builder returned null → keep last-known-good")
    void nullCandidateKeepsLkg() {
        // given
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));

        // when
        boolean applied = holder.tryUpdate(prev -> null);

        // then
        assertThat(applied).isFalse();
        assertThat(holder.current().value()).isEqualTo(7);
        assertThat(holder.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("builder threw exception → keep last-known-good, not rethrown")
    void builderThrowKeepsLkg() {
        // given
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));

        // when
        boolean applied = holder.tryUpdate(prev -> {
            throw new IllegalStateException("bad input");
        });

        // then
        assertThat(applied).isFalse();
        assertThat(holder.current().value()).isEqualTo(7);
    }

    @Test
    @DisplayName("validator rejected candidate → keep last-known-good")
    void validatorRejectKeepsLkg() {
        // given
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));

        // when
        boolean applied = holder.tryUpdate(prev -> new Snapshot(2, 999), s -> s.value() <= 100);

        // then
        assertThat(applied).isFalse();
        assertThat(holder.current().value()).isEqualTo(7);
    }

    @Test
    @DisplayName("validator threw exception → keep last-known-good")
    void validatorThrowKeepsLkg() {
        // given
        VersionedStateHolder<Snapshot> holder = holder();
        holder.tryUpdate(prev -> new Snapshot(1, 7));

        // when
        boolean applied = holder.tryUpdate(prev -> new Snapshot(2, 8), s -> {
            throw new RuntimeException("validator boom");
        });

        // then
        assertThat(applied).isFalse();
        assertThat(holder.current().value()).isEqualTo(7);
    }

    @Test
    @DisplayName("null builder → NullPointerException, state unchanged")
    void nullBuilderThrowsAndKeepsState() {
        // given
        VersionedStateHolder<Snapshot> holder = holder();

        // when
        // then
        assertThatThrownBy(() -> holder.tryUpdate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("builder");
        assertThat(holder.version()).isZero();
    }

    @Test
    @DisplayName("concurrent updates: all applied, versions monotonic, no loss (CAS-retry)")
    void concurrentUpdatesAreSerializedWithoutLoss() throws InterruptedException {
        // given
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

        // when
        start.countDown();
        pool.shutdown();

        // then
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        int expected = threads * perThread;
        assertThat(appliedCount.get()).isEqualTo(expected);
        assertThat(holder.current().value()).isEqualTo(expected);
        assertThat(holder.version()).isEqualTo(expected);
    }
}
