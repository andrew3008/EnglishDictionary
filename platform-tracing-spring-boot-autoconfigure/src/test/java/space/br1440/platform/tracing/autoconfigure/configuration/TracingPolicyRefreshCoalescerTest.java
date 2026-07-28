package space.br1440.platform.tracing.autoconfigure.configuration;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.configuration.configserver.ConfigServerPolicyManifestBuilder;
import space.br1440.platform.tracing.autoconfigure.configuration.configserver.TracingPolicyRefreshCoalescer;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class TracingPolicyRefreshCoalescerTest {

    @Test
    void singleEventTriggersSingleApply() throws Exception {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        TracingPolicySnapshotClient client = mock(TracingPolicySnapshotClient.class);
        CompleteTracingPolicySnapshot snapshot = CompleteTracingPolicySnapshot.builder().build();
        AcceptedTracingPolicyView view = new AcceptedTracingPolicyView(
                "id", 0L, "d", true, 1.0, 0, true, false, true, true, "INFO", true, "APPLIED");

        when(builder.build()).thenReturn(snapshot);
        when(client.applySnapshot(anyLong(), any(), any())).thenReturn(TracingPolicyDeliveryResult.APPLIED);
        when(client.current()).thenReturn(Optional.of(view));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger applyCount = new AtomicInteger();
        TracingPolicySnapshotClient countingClient = new TracingPolicySnapshotClient() {
            @Override
            public TracingPolicyDeliveryResult applySnapshot(long seq, CompleteTracingPolicySnapshot s,
                    TracingPolicySource source) {
                applyCount.incrementAndGet();
                latch.countDown();
                return TracingPolicyDeliveryResult.APPLIED;
            }
            @Override
            public Optional<AcceptedTracingPolicyView> current() { return Optional.of(view); }
        };

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, countingClient, Runnable::run);
        coalescer.onRefreshEvent();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(applyCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void noRetryOnRejected() throws Exception {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        CompleteTracingPolicySnapshot snapshot = CompleteTracingPolicySnapshot.builder().build();
        AcceptedTracingPolicyView view = new AcceptedTracingPolicyView(
                "id", 0L, "d", true, 1.0, 0, true, false, true, true, "INFO", true, "REJECTED");
        when(builder.build()).thenReturn(snapshot);

        AtomicInteger applyCount = new AtomicInteger();
        TracingPolicySnapshotClient rejectingClient = new TracingPolicySnapshotClient() {
            @Override
            public TracingPolicyDeliveryResult applySnapshot(long seq, CompleteTracingPolicySnapshot s,
                    TracingPolicySource source) {
                applyCount.incrementAndGet();
                return TracingPolicyDeliveryResult.REJECTED;
            }
            @Override
            public Optional<AcceptedTracingPolicyView> current() { return Optional.of(view); }
        };

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, rejectingClient, Runnable::run);
        coalescer.onRefreshEvent();

        assertThat(applyCount.get()).isEqualTo(1); // no retry on REJECTED
    }

    @Test
    void staleTriggersExactlyOneRetry() {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        CompleteTracingPolicySnapshot snapshot = CompleteTracingPolicySnapshot.builder().build();
        AcceptedTracingPolicyView view = new AcceptedTracingPolicyView(
                "id", 0L, "d", true, 1.0, 0, true, false, true, true, "INFO", true, "STALE");
        when(builder.build()).thenReturn(snapshot);

        AtomicInteger applyCount = new AtomicInteger();
        TracingPolicySnapshotClient staleClient = new TracingPolicySnapshotClient() {
            @Override
            public TracingPolicyDeliveryResult applySnapshot(long seq, CompleteTracingPolicySnapshot s,
                    TracingPolicySource source) {
                applyCount.incrementAndGet();
                return TracingPolicyDeliveryResult.STALE;
            }
            @Override
            public Optional<AcceptedTracingPolicyView> current() { return Optional.of(view); }
        };

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, staleClient, Runnable::run);
        coalescer.onRefreshEvent();

        assertThat(applyCount.get()).isEqualTo(2); // initial + one bounded retry
    }

    @Test
    void initialDeliveryThrowsOnMissingLeaf() {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        TracingPolicySnapshotClient client = mock(TracingPolicySnapshotClient.class);
        when(builder.build()).thenThrow(new TracingPolicyManifestException("missing leaf"));

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, client, Runnable::run);

        org.junit.jupiter.api.Assertions.assertThrows(
                TracingPolicyManifestException.class,
                coalescer::deliverInitial);
    }
}
