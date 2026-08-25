package space.br1440.platform.tracing.otel.javaagent.control;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitOutcome;
import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;

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
                TracingSettingsStore store = new TracingSettingsStore(
                        AcceptedPolicyTestFixtures.RUNTIME_INSTANCE_ID,
                        AcceptedPolicyTestFixtures.policy(0.1d));
                ExpectedSettingsRevision expected = store.current().base();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<SettingsCommitResult>> futures = new ArrayList<>();

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
                List<SettingsCommitOutcome> outcomes = new ArrayList<>();
                for (Future<SettingsCommitResult> future : futures) {
                    outcomes.add(future.get().outcome());
                }

                assertThat(outcomes).containsOnlyOnce(SettingsCommitOutcome.APPLIED);
                assertThat(outcomes).filteredOn(SettingsCommitOutcome.STALE::equals).hasSize(7);
                assertThat(store.current().acceptedSequence()).isEqualTo(1L);
            }
        }
    }

    @Test
    void runtimeAndSequenceMismatchAreStaleWithoutMutation() {
        TracingSettingsStore store = new TracingSettingsStore(
                AcceptedPolicyTestFixtures.RUNTIME_INSTANCE_ID,
                AcceptedPolicyTestFixtures.policy(0.1d));
        TracingSettingsRevision initial = store.current();

        SettingsCommitResult runtimeMismatch = store.commit(
                new ExpectedSettingsRevision(
                        "00000000-0000-0000-0000-000000000002",
                        0L),
                AcceptedPolicyTestFixtures.policy(0.2d));
        SettingsCommitResult sequenceMismatch = store.commit(
                new ExpectedSettingsRevision(AcceptedPolicyTestFixtures.RUNTIME_INSTANCE_ID, 1L),
                AcceptedPolicyTestFixtures.policy(0.3d));

        assertThat(runtimeMismatch.outcome()).isEqualTo(SettingsCommitOutcome.STALE);
        assertThat(sequenceMismatch.outcome()).isEqualTo(SettingsCommitOutcome.STALE);
        assertThat(store.current()).isSameAs(initial);
    }

    @Test
    void storeHasOneAtomicOwnerAndNoGenericPayload() {
        Set<Field> instanceFields = Set.of(TracingSettingsStore.class.getDeclaredFields());

        assertThat(instanceFields).hasSize(1);
        assertThat(instanceFields.iterator().next().getType()).isEqualTo(AtomicReference.class);
        assertThat(TracingSettingsStore.class.getTypeParameters()).isEmpty();
    }

    @Test
    void currentReadAllocatesNoMemoryOnHotSpot() {
        java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(standardBean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean allocationBean =
                (com.sun.management.ThreadMXBean) standardBean;
        Assumptions.assumeTrue(allocationBean.isThreadAllocatedMemorySupported());
        allocationBean.setThreadAllocatedMemoryEnabled(true);

        TracingSettingsStore store = new TracingSettingsStore(
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
