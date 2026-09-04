package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;

class TracingSettingsRefreshCoalescerTest {

    private static final String RUNTIME_INSTANCE_ID = "instance-1";

    private static CompleteTracingSettings sampleSnapshot() {
        return CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of())
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
    }

    private static CompleteTracingSettingsReader readerReturning(long sequence) {
        AcceptedSettingsWithSequence accepted =
                new AcceptedSettingsWithSequence(sequence, RUNTIME_INSTANCE_ID, sampleSnapshot());
        return () -> Optional.of(accepted);
    }

    @Test
    void singleEventTriggersSingleApply() throws Exception {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger applyCount = new AtomicInteger();
        TracingSettingsApplier countingClient = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            latch.countDown();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, countingClient, readerReturning(1L), Runnable::run);
        coalescer.onRefreshEvent();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(applyCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void noRetryOnRejected() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger applyCount = new AtomicInteger();
        TracingSettingsApplier rejectingClient = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.REJECTED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, rejectingClient, readerReturning(1L), Runnable::run);
        coalescer.onRefreshEvent();

        assertThat(applyCount.get()).isEqualTo(1);
    }

    @Test
    void staleTriggersExactlyOneRetry() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger applyCount = new AtomicInteger();
        TracingSettingsApplier staleClient = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.STALE;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, staleClient, readerReturning(1L), Runnable::run);
        coalescer.onRefreshEvent();

        assertThat(applyCount.get()).isEqualTo(2);
    }

    @Test
    void initialDeliveryThrowsOnMissingLeaf() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        TracingSettingsApplier client = mock(TracingSettingsApplier.class);
        when(builder.build()).thenThrow(new TracingSettingsManifestException("missing leaf"));

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, readerReturning(1L), Runnable::run);

        org.junit.jupiter.api.Assertions.assertThrows(
                TracingSettingsManifestException.class,
                coalescer::deliverInitial);
    }

    @Test
    void initialDeliveryThrowsOnInvalidValue() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        TracingSettingsApplier client = mock(TracingSettingsApplier.class);
        when(builder.build()).thenThrow(new IllegalArgumentException("ratio must be finite and within [0.0, 1.0]"));

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, readerReturning(1L), Runnable::run);

        assertThatThrownBy(coalescer::deliverInitial)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ratio must be finite");
    }

    @Test
    void runtimeInvalidValueRetainsLkgWithoutApply() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build())
                .thenReturn(sampleSnapshot())
                .thenThrow(new IllegalArgumentException("route prefix must not be blank"));

        AtomicInteger applyCount = new AtomicInteger();
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, readerReturning(1L), Runnable::run);
        assertThat(coalescer.deliverInitial()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        assertThat(applyCount.get()).isEqualTo(1);

        coalescer.onRefreshEvent();

        assertThat(applyCount.get())
                .as("invalid value refresh must not call applySnapshot; LKG retained")
                .isEqualTo(1);
    }

    @Test
    void initialDeliveryWarmsSequenceFromAgentBeforeApplying() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger observedSeq = new AtomicInteger(Integer.MIN_VALUE);
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            observedSeq.set((int) expectedBase.acceptedSequence());
            return TracingSettingsDeliveryResult.APPLIED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, readerReturning(7L), Runnable::run);
        TracingSettingsDeliveryResult result = coalescer.deliverInitial();

        assertThat(result).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        assertThat(observedSeq.get()).isEqualTo(7);
    }

    @Test
    void initialDeliveryWithRealClient_authoritativeReadThenApply_roundTrips() throws Exception {
        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.compliant("instance-real-1", 5L)) {
            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, client, Runnable::run);

            TracingSettingsDeliveryResult result = coalescer.deliverInitial();

            assertThat(result).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
            assertThat(agent.currentAcceptedSequence())
                    .as("a real applySnapshot round trip must have reached the fake Agent exactly once")
                    .isEqualTo(6L);
        }
    }

    @Test
    void initialDeliveryWithRealClient_unchangedDelivery_reportsUnchanged() throws Exception {
        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.compliant("instance-real-2", 9L)) {
            agent.setNextApplyOutcome("UNCHANGED");
            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, client, Runnable::run);

            assertThat(coalescer.deliverInitial()).isEqualTo(TracingSettingsDeliveryResult.UNCHANGED);
        }
    }

    @Test
    void initialDeliveryWithRealClient_readFailure_blocksWithoutFallback() throws Exception {
        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.readFailingAcceptedPolicySnapshot("instance-real-3")) {
            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, client, Runnable::run);

            assertThatThrownBy(coalescer::deliverInitial)
                    .as("a read failure must block delivery, not fall back to a default/unconditional apply")
                    .isInstanceOf(RuntimeException.class);
            assertThat(agent.applyInvocationCount()).isZero();
        }
    }

    @Test
    void initialDeliveryWithRealClient_malformedResponse_neverSendsUnwarmedSequence() throws Exception {
        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.malformedAcceptedPolicySnapshot("instance-real-4")) {
            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, client, Runnable::run);

            assertThatThrownBy(coalescer::deliverInitial)
                    .as("an unwarmed expected base must never reach applySnapshot; the coalescer "
                            + "must fail closed instead")
                    .isInstanceOf(TracingSettingsContractViolationException.class)
                    .hasMessageContaining("JMX_CLIENT_STATUS_RESPONSE_DECODE_FAILED");
            assertThat(agent.applyInvocationCount()).isZero();
        }
    }

    @Test
    void realClient_agentRestartWithNewInstanceAndResetSequence_recoversOnNextRead() throws Exception {
        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.compliant("instance-before-restart", 3L)) {
            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, client, Runnable::run);
            assertThat(coalescer.deliverInitial()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
            assertThat(agent.currentAcceptedSequence()).isEqualTo(4L);

            agent.restartWithNewInstance("instance-after-restart", 0L);

            TracingSettingsDeliveryResult postRestart = coalescer.deliverInitial();

            assertThat(postRestart).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
            assertThat(agent.currentAcceptedSequence())
                    .as("apply after restart must be evaluated against the new instance's own sequence, not "
                            + "the pre-restart cached value")
                    .isEqualTo(1L);
        }
    }
}
