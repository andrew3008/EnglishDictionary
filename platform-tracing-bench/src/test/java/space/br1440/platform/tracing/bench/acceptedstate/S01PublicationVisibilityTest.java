package space.br1440.platform.tracing.bench.acceptedstate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate E3 publication proof: readers observe fully constructed accepted state with no reader-side
 * synchronisation, and never observe a torn pair of policy and accepted sequence.
 * <p>
 * The writer establishes the invariant {@code acceptedSequence == policy.seed()} on every commit. A
 * reader that ever observes a violation of that invariant has observed a state where the sequence and
 * the policy came from different commits. That is exactly the failure mode that two separate atomics
 * for policy and sequence would produce, so this assertion is the executable argument for keeping both
 * inside one immutable envelope behind one reference.
 * <p>
 * {@link S01Policy#selfConsistent()} additionally rejects a partially constructed policy: every field
 * is derived from the seed, so default field values or a {@code null} reference field would be
 * detected.
 */
@DisplayName("S-01: readers see fully constructed, non-torn accepted state without synchronisation")
class S01PublicationVisibilityTest {

    private static final String RUNTIME_INSTANCE_ID = "s01-runtime";
    private static final int READERS = 6;
    private static final long COMMITS = 20_000L;

    @Test
    @DisplayName("continuous commits → no partial policy, no torn sequence/policy pair")
    void readersNeverObserveTornOrPartialState() throws Exception {
        // given
        S01AcceptedPolicyStore<S01Policy> store =
                new S01AcceptedPolicyStore<>(RUNTIME_INSTANCE_ID, S01Policy.of(0L));
        AtomicReference<String> violation = new AtomicReference<>();
        AtomicBoolean writerDone = new AtomicBoolean();
        AtomicLong observations = new AtomicLong();
        CountDownLatch startTogether = new CountDownLatch(1);

        List<Thread> readers = new ArrayList<>(READERS);
        for (int i = 0; i < READERS; i++) {
            Thread reader = new Thread(() -> {
                try {
                    startTogether.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long seen = 0L;
                while (!writerDone.get()) {
                    // The entire hot-path read: one reference load, then plain final-field reads.
                    S01AcceptedEnvelope<S01Policy> envelope = store.current();
                    S01Policy policy = envelope.policy();
                    if (!policy.selfConsistent()) {
                        violation.compareAndSet(null,
                                "partially constructed policy observed at seed " + policy.seed());
                        return;
                    }
                    if (envelope.acceptedSequence() != policy.seed()) {
                        violation.compareAndSet(null, "torn envelope: acceptedSequence="
                                + envelope.acceptedSequence() + " but policy.seed=" + policy.seed());
                        return;
                    }
                    seen++;
                }
                observations.addAndGet(seen);
            }, "s01-reader-" + i);
            reader.setDaemon(true);
            readers.add(reader);
            reader.start();
        }

        // when
        startTogether.countDown();
        for (long commit = 1L; commit <= COMMITS; commit++) {
            S01ExpectedBase base = store.current().expectedBase();
            S01CommitResult<S01Policy> result =
                    store.commit(base, S01Policy.of(base.acceptedSequence() + 1L));
            assertThat(result.outcome())
                    .as("the single writer always matches the base it just read")
                    .isEqualTo(S01CommitOutcome.APPLIED);
        }
        writerDone.set(true);

        for (Thread reader : readers) {
            reader.join(TimeUnit.SECONDS.toMillis(30));
            assertThat(reader.isAlive()).as("reader %s terminated", reader.getName()).isFalse();
        }

        // then
        assertThat(violation.get()).isNull();
        assertThat(store.current().acceptedSequence()).isEqualTo(COMMITS);
        assertThat(observations.get())
                .as("readers actually exercised the hot path")
                .isPositive();
    }

    @Test
    @DisplayName("accepted envelope and policy are immutable, so a published state cannot change later")
    void publishedStateIsImmutable() {
        // given
        S01AcceptedPolicyStore<S01Policy> store =
                new S01AcceptedPolicyStore<>(RUNTIME_INSTANCE_ID, S01Policy.of(3L));
        S01AcceptedEnvelope<S01Policy> observed = store.current();

        // when: later commits do not touch the previously observed envelope
        store.commit(observed.expectedBase(), S01Policy.of(4L));

        // then
        assertThat(observed.acceptedSequence()).isZero();
        assertThat(observed.policy()).isEqualTo(S01Policy.of(3L));
        assertThat(observed.policy().selfConsistent()).isTrue();
        assertThat(store.current().policy()).isEqualTo(S01Policy.of(4L));
    }
}
