package space.br1440.platform.tracing.otel.javaagent.control.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class AcceptedPolicyStateStoreTest {

    private static volatile long allocationSink;

    @Test
    void admitsExactlyOneWinnerForTheSameExpectedBase() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int round = 0; round < 100; round++) {
                AcceptedPolicyStateStore store = new AcceptedPolicyStateStore(
                        AcceptedPolicyTestFixtures.RUNTIME_INSTANCE_ID,
                        AcceptedPolicyTestFixtures.policy(0.1d));
                ExpectedPolicyBase expected = store.current().base();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<PolicyCommitResult>> futures = new ArrayList<>();

                for (int writer = 1; writer <= 8; writer++) {
                    double ratio = writer / 10.0d;
                    futures.add(executor.submit(() -> {
                        start.await();
                        return store.commit(
                                expected,
                                AcceptedPolicyTestFixtures.policy(ratio));
                    }));
                }

                start.countDown();
                List<PolicyCommitOutcome> outcomes = new ArrayList<>();
                for (Future<PolicyCommitResult> future : futures) {
                    outcomes.add(future.get().outcome());
                }

                assertThat(outcomes).containsOnlyOnce(PolicyCommitOutcome.APPLIED);
                assertThat(outcomes).filteredOn(PolicyCommitOutcome.STALE::equals).hasSize(7);
                assertThat(store.current().acceptedSequence()).isEqualTo(1L);
            }
        }
    }

    @Test
    void runtimeAndSequenceMismatchAreStaleWithoutMutation() {
        AcceptedPolicyStateStore store = new AcceptedPolicyStateStore(
                AcceptedPolicyTestFixtures.RUNTIME_INSTANCE_ID,
                AcceptedPolicyTestFixtures.policy(0.1d));
        AcceptedPolicyEnvelope initial = store.current();

        PolicyCommitResult runtimeMismatch = store.commit(
                new ExpectedPolicyBase(
                        "00000000-0000-0000-0000-000000000002",
                        0L),
                AcceptedPolicyTestFixtures.policy(0.2d));
        PolicyCommitResult sequenceMismatch = store.commit(
                new ExpectedPolicyBase(AcceptedPolicyTestFixtures.RUNTIME_INSTANCE_ID, 1L),
                AcceptedPolicyTestFixtures.policy(0.3d));

        assertThat(runtimeMismatch.outcome()).isEqualTo(PolicyCommitOutcome.STALE);
        assertThat(sequenceMismatch.outcome()).isEqualTo(PolicyCommitOutcome.STALE);
        assertThat(store.current()).isSameAs(initial);
    }

    @Test
    void storeHasOneAtomicOwnerAndNoGenericPayload() {
        Set<Field> instanceFields = Set.of(AcceptedPolicyStateStore.class.getDeclaredFields());

        assertThat(instanceFields).hasSize(1);
        assertThat(instanceFields.iterator().next().getType()).isEqualTo(AtomicReference.class);
        assertThat(AcceptedPolicyStateStore.class.getTypeParameters()).isEmpty();
    }

    @Test
    void currentReadAllocatesNoMemoryOnHotSpot() {
        java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(standardBean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean allocationBean =
                (com.sun.management.ThreadMXBean) standardBean;
        Assumptions.assumeTrue(allocationBean.isThreadAllocatedMemorySupported());
        allocationBean.setThreadAllocatedMemoryEnabled(true);

        AcceptedPolicyStateStore store = new AcceptedPolicyStateStore(
                AcceptedPolicyTestFixtures.RUNTIME_INSTANCE_ID,
                AcceptedPolicyTestFixtures.policy(0.1d));
        long sum = 0L;
        for (int index = 0; index < 200_000; index++) {
            sum += store.current().acceptedSequence();
        }

        long threadId = Thread.currentThread().threadId();
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        for (int index = 0; index < 2_000_000; index++) {
            sum += store.current().acceptedSequence();
        }
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        allocationSink = sum;

        assertThat(allocated).isZero();
    }
}
