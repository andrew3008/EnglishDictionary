package space.br1440.platform.tracing.bench.acceptedstate;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Gate E3 hot-path allocation proof, measured with the HotSpot per-thread allocation counter.
 * <p>
 * This complements the {@code gc.alloc.rate.norm} figure from
 * {@code S01AcceptedStateReadBenchmark}: the JMH profiler reports a statistical bytes-per-operation
 * average, whereas {@link ThreadMXBean#getCurrentThreadAllocatedBytes()} is an exact cumulative
 * counter, so it can assert a hard zero rather than a rounded average. Both are reported as evidence.
 * <p>
 * Deliberately excluded from the measured region: warm-up, assertion machinery, and anything that
 * boxes. The measured loop accumulates into primitive locals only.
 */
@DisplayName("S-01: hot-path read allocates zero bytes")
class S01HotPathAllocationTest {

    private static final String RUNTIME_INSTANCE_ID = "s01-runtime";
    private static final int WARMUP_READS = 200_000;
    private static final int MEASURED_READS = 2_000_000;

    @Test
    @DisplayName("2M accepted-state reads allocate 0 bytes on the reading thread")
    void hotPathReadIsAllocationFree() {
        // given
        ThreadMXBean threads = allocationCountingThreadMxBean();
        assumeTrue(threads != null, "per-thread allocation counter unavailable on this JVM");
        assumeTrue(threads.isThreadAllocatedMemoryEnabled(),
                "per-thread allocation measurement disabled on this JVM");

        S01AcceptedPolicyStore<S01Policy> store =
                new S01AcceptedPolicyStore<>(RUNTIME_INSTANCE_ID, S01Policy.of(11L));

        double warmupSink = 0.0d;
        int warmupCount = 0;
        for (int i = 0; i < WARMUP_READS; i++) {
            S01AcceptedEnvelope<S01Policy> envelope = store.current();
            warmupSink += envelope.policy().ratio();
            warmupCount += envelope.policy().routeRatios().size();
        }

        // when
        long before = threads.getCurrentThreadAllocatedBytes();
        double ratioSink = 0.0d;
        long sequenceSink = 0L;
        int sizeSink = 0;
        for (int i = 0; i < MEASURED_READS; i++) {
            S01AcceptedEnvelope<S01Policy> envelope = store.current();
            S01Policy policy = envelope.policy();
            ratioSink += policy.ratio();
            sequenceSink += envelope.acceptedSequence();
            sizeSink += policy.routeRatios().size();
        }
        long allocatedBytes = threads.getCurrentThreadAllocatedBytes() - before;

        // then
        assertThat(allocatedBytes)
                .as("accepted-state read path must allocate nothing; measured %d bytes over %d reads",
                        allocatedBytes, MEASURED_READS)
                .isZero();

        // Keep the measured loop observable so it cannot be optimised away entirely. The ratio sink
        // is compared with a tolerance because repeated double addition accumulates rounding error.
        assertThat(sizeSink).isEqualTo(MEASURED_READS);
        assertThat(sequenceSink).isZero();
        assertThat(ratioSink)
                .isCloseTo(MEASURED_READS * S01Policy.of(11L).ratio(), within(1.0d));
        assertThat(warmupCount).isEqualTo(WARMUP_READS);
        assertThat(warmupSink).isPositive();
    }

    @Test
    @DisplayName("read path takes no lock and no monitor: it is a single volatile reference load")
    void hotPathReadHoldsNoLock() {
        // given
        S01AcceptedPolicyStore<S01Policy> store =
                new S01AcceptedPolicyStore<>(RUNTIME_INSTANCE_ID, S01Policy.of(1L));

        // when
        S01AcceptedEnvelope<S01Policy> envelope = store.current();

        // then: no synchronized member exists anywhere on the store, so no read can block
        assertThat(envelope).isNotNull();
        assertThat(S01AcceptedPolicyStore.class.getDeclaredMethods())
                .as("no method on the store is synchronized")
                .allSatisfy(method -> assertThat(java.lang.reflect.Modifier
                        .isSynchronized(method.getModifiers())).isFalse());
        assertThat(S01AcceptedPolicyStore.class.getDeclaredFields())
                .as("the store holds exactly one field: the atomic accepted-state reference")
                .hasSize(1);
    }

    private static ThreadMXBean allocationCountingThreadMxBean() {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        return (platformBean instanceof ThreadMXBean hotspotBean) ? hotspotBean : null;
    }
}
