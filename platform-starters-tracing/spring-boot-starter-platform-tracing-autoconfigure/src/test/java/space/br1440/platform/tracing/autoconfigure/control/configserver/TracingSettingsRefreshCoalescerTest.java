package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter.recordingSettingsWriter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettingsAuthority;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.support.AcceptedSettingsTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;
import space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class TracingSettingsRefreshCoalescerTest {

    private static final String AGENT_INSTANCE_ID = "e326dc12-3447-42d8-8674-3699e783b5c0";
    private static final String BEFORE_RESTART_AGENT_INSTANCE_ID = "be807e0b-32e9-4628-9243-0f20f2bc68e6";
    private static final String AFTER_RESTART_AGENT_INSTANCE_ID = "dd97eef0-5d5b-43ea-9b66-bafb8e4168d2";

    private static CompleteTracingSettings sampleSnapshot() {
        return CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of())
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
    }

    private static Supplier<Optional<AcceptedSettings>> readerReturning(long sequence) {
        AcceptedSettings accepted = AcceptedSettingsTestFixtures.configServerAccepted(sequence, AGENT_INSTANCE_ID, sampleSnapshot());
        return () -> Optional.of(accepted);
    }

    @Test
    void singleEventTriggersSingleApply() throws Exception {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger applyCount = new AtomicInteger();
        RecordingSettingsWriter.ApplyBehavior countingClient = (expectedBase, s, writer) -> {
            applyCount.incrementAndGet();
            latch.countDown();
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(countingClient, readerReturning(1L)), Runnable::run);
        coalescer.onRefreshEvent();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(applyCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void noRetryOnRejected() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger applyCount = new AtomicInteger();
        RecordingSettingsWriter.ApplyBehavior rejectingClient = (expectedBase, s, writer) -> {
            applyCount.incrementAndGet();
            return delivery(TracingSettingsDeliveryResult.REJECTED);
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(rejectingClient, readerReturning(1L)), Runnable::run);
        coalescer.onRefreshEvent();

        assertThat(applyCount.get()).isEqualTo(1);
    }

    @Test
    void staleRetryRebuildsTheManifest() {
        // given
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        CompleteTracingSettings first = sampleSnapshot();
        CompleteTracingSettings rebuilt = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of())
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("WARN")
                .build();
        when(builder.build()).thenReturn(first, rebuilt);

        List<CompleteTracingSettings> seen = new ArrayList<>();
        RecordingSettingsWriter.ApplyBehavior staleClient = (expectedBase, snapshot, writer) -> {
            seen.add(snapshot);
            return delivery(TracingSettingsDeliveryResult.STALE);
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(staleClient, readerReturning(1L)), Runnable::run);

        // when
        coalescer.onRefreshEvent();

        // then
        assertThat(seen).containsExactly(first, rebuilt);
        verify(builder, times(2)).build();
    }

    @Test
    void staleRetryRebuildFailureRetainsLkgWithoutSecondApply() {
        // given
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build())
                .thenReturn(sampleSnapshot())
                .thenThrow(new TracingSettingsManifestException("leaf missing on retry"));

        AtomicInteger applyCount = new AtomicInteger();
        RecordingSettingsWriter.ApplyBehavior staleClient = (expectedBase, snapshot, writer) -> {
            applyCount.incrementAndGet();
            return delivery(TracingSettingsDeliveryResult.STALE);
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(staleClient, readerReturning(1L)), Runnable::run);

        // when
        coalescer.onRefreshEvent();

        // then
        assertThat(applyCount.get()).isEqualTo(1);
    }

    @Test
    void initialDeliveryThrowsOnMissingLeaf() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        RecordingSettingsWriter.ApplyBehavior client = mock(RecordingSettingsWriter.ApplyBehavior.class);
        when(builder.build()).thenThrow(new TracingSettingsManifestException("missing leaf"));

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, readerReturning(1L)), Runnable::run);

        org.junit.jupiter.api.Assertions.assertThrows(
                TracingSettingsManifestException.class,
                coalescer::deliverInitial);
    }

    @Test
    void initialDeliveryThrowsOnInvalidValue() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        RecordingSettingsWriter.ApplyBehavior client = mock(RecordingSettingsWriter.ApplyBehavior.class);
        when(builder.build()).thenThrow(new IllegalArgumentException("ratio must be finite and within [0.0, 1.0]"));

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, readerReturning(1L)), Runnable::run);

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
        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, s, writer) -> {
            applyCount.incrementAndGet();
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, readerReturning(1L)), Runnable::run);
        assertThat(coalescer.deliverInitial().outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
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
        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, s, writer) -> {
            observedSeq.set((int) expectedBase.acceptedSequence());
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, readerReturning(7L)), Runnable::run);
        SettingsDelivery result = coalescer.deliverInitial();

        assertThat(result.outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        assertThat(observedSeq.get()).isEqualTo(7);
    }

    @Test
    void initialDeliveryWithRealClient_authoritativeReadThenApply_roundTrips() throws Exception {
        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.compliant(
                "10000000-0000-4000-8000-000000000001",
                5L
        )) {
            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, Runnable::run);

            SettingsDelivery result = coalescer.deliverInitial();

            assertThat(result.outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
            assertThat(agent.currentAcceptedSequence())
                    .as("a real applySnapshot round trip must have reached the fake Agent exactly once")
                    .isEqualTo(6L);
        }
    }

    @Test
    void initialDeliveryWithRealClient_unchangedDelivery_reportsUnchanged() throws Exception {
        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.compliant(
                "10000000-0000-4000-8000-000000000002",
                9L
        )) {
            agent.setNextApplyOutcome("UNCHANGED");
            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, Runnable::run);

            assertThat(coalescer.deliverInitial().outcome()).isEqualTo(TracingSettingsDeliveryResult.UNCHANGED);
        }
    }

    @Test
    void initialDeliveryWithRealClient_readFailure_blocksWithoutFallback() throws Exception {
        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.readFailingAcceptedPolicySnapshot(
                "10000000-0000-4000-8000-000000000003"
        )) {
            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, Runnable::run);

            assertThatThrownBy(coalescer::deliverInitial)
                    .as("a read failure must block delivery, not fall back to a default/unconditional apply")
                    .isInstanceOf(RuntimeException.class);
            assertThat(agent.applyInvocationCount()).isZero();
        }
    }

    @Test
    void initialDeliveryWithRealClient_malformedResponse_neverSendsUnwarmedSequence() throws Exception {
        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.malformedAcceptedPolicySnapshot(
                "10000000-0000-4000-8000-000000000004"
        )) {
            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, Runnable::run);

            assertThatThrownBy(coalescer::deliverInitial)
                    .as("an unwarmed expected base must never reach applySnapshot; the coalescer "
                            + "must fail closed instead")
                    .isInstanceOf(TracingSettingsContractViolationException.class)
                    .hasMessageContaining("JMX_CLIENT_STATUS_RESPONSE_DECODE_FAILED");
            assertThat(agent.applyInvocationCount()).isZero();
        }
    }

    @Test
    void realClientAgentRestartFailsClosedBeforeNextRead() throws Exception {
        // given
        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.compliant(
                BEFORE_RESTART_AGENT_INSTANCE_ID,
                3L
        )) {
            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, Runnable::run);
            assertThat(coalescer.deliverInitial().outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
            assertThat(agent.currentAcceptedSequence()).isEqualTo(4L);

            agent.restartWithNewInstance(AFTER_RESTART_AGENT_INSTANCE_ID, 0L);

            // when
            // then
            assertThatThrownBy(coalescer::deliverInitial)
                    .isInstanceOf(AgentReadinessCoherenceException.class)
                    .satisfies(failure -> assertThat(
                            ((AgentReadinessCoherenceException) failure).failureCode())
                            .isEqualTo(AgentReadinessCoherenceGuard.PINNED_ANCHOR_CHANGED));
            assertThat(agent.applyInvocationCount()).isEqualTo(1);
            assertThat(agent.currentAcceptedSequence()).isZero();
        }
    }

    @Test
    void refreshFailureIsLoggedOnceAndNextRefreshStillReachesWriter() {
        // given
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());
        AtomicInteger applyCount = new AtomicInteger();
        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, snapshot, writer) -> {
            if (applyCount.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated refresh failure");
            }
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };
        Logger logger = (Logger) LoggerFactory.getLogger(TracingSettingsRefreshCoalescer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder,
                recordingSettingsWriter(client, readerReturning(1L)),
                Runnable::run
        );

        try {
            // when
            coalescer.onRefreshEvent();
            coalescer.onRefreshEvent();

            // then
            assertThat(applyCount).hasValue(2);
            assertThat(appender.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .filter(event -> event.getFormattedMessage()
                            .startsWith("Config Server settings refresh failed; LKG retained."))
                    .count()).isEqualTo(1L);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void refreshArrivingDuringFailedApplyIsRescheduled() {
        // given
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());
        Queue<Runnable> tasks = new ArrayDeque<>();
        AtomicInteger applyCount = new AtomicInteger();
        AtomicReference<TracingSettingsRefreshCoalescer> coalescerRef = new AtomicReference<>();
        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, snapshot, writer) -> {
            if (applyCount.incrementAndGet() == 1) {
                coalescerRef.get().onRefreshEvent();
                throw new IllegalStateException("simulated refresh failure");
            }
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder,
                recordingSettingsWriter(client, readerReturning(1L)),
                tasks::add
        );
        coalescerRef.set(coalescer);

        // when
        coalescer.onRefreshEvent();
        tasks.remove().run();
        tasks.remove().run();

        // then
        assertThat(applyCount).hasValue(2);
        assertThat(tasks).isEmpty();
    }

    @Test
    void coalescerAndTransportLogsUseTheSameAttemptId() throws Exception {
        // given
        Logger coalescerLogger = (Logger) LoggerFactory.getLogger(TracingSettingsRefreshCoalescer.class);
        Logger transportLogger = (Logger) LoggerFactory.getLogger(
                "space.br1440.platform.tracing.autoconfigure.control.jmx.JmxSettingsMutationTransport"
        );
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        coalescerLogger.addAppender(appender);
        transportLogger.addAppender(appender);

        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.compliant(
                "10000000-0000-4000-8000-000000000005",
                0L
        )) {
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());
            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, agent.client(), Runnable::run);

            // when
            assertThat(coalescer.deliverInitial().outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);

            // then
            List<String> correlatedMessages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("attemptId="))
                    .toList();
            assertThat(correlatedMessages)
                    .anyMatch(message -> message.startsWith("Config Server settings delivery: phase=INITIAL"))
                    .anyMatch(message -> message.startsWith("Tracing settings mutation completed:"));
            List<String> attemptIds = correlatedMessages.stream()
                    .map(TracingSettingsRefreshCoalescerTest::extractAttemptId)
                    .distinct()
                    .toList();
            assertThat(attemptIds).hasSize(1);
            assertThat(attemptIds.get(0)).isNotBlank();
        } finally {
            coalescerLogger.detachAppender(appender);
            transportLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void rejectedExecutionClearsScheduleAndNextEventReachesWriter() {
        // given
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());
        AtomicInteger applyCount = new AtomicInteger();
        AtomicBoolean rejectExecution = new AtomicBoolean(true);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder,
                recordingSettingsWriter((expectedBase, snapshot, writer) -> {
                    applyCount.incrementAndGet();
                    return delivery(TracingSettingsDeliveryResult.APPLIED);
                }, readerReturning(1L)),
                task -> {
                    if (rejectExecution.getAndSet(false)) {
                        throw new RejectedExecutionException("worker is shut down");
                    }
                    task.run();
                }
        );

        // when
        coalescer.onRefreshEvent();
        coalescer.onRefreshEvent();

        // then
        assertThat(applyCount).hasValue(1);
    }

    @Test
    void actuatorAuthorityWarnsAndStillDelivers() {
        // given
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());
        AcceptedSettings actuator = new AcceptedSettings(
                new SettingsRevision(AGENT_INSTANCE_ID, 1L),
                sampleSnapshot(),
                AcceptedSettingsAuthority.ACTUATOR
        );
        AtomicInteger applyCount = new AtomicInteger();
        Logger logger = (Logger) LoggerFactory.getLogger(TracingSettingsRefreshCoalescer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder,
                recordingSettingsWriter((expectedBase, snapshot, writer) -> {
                    applyCount.incrementAndGet();
                    return delivery(TracingSettingsDeliveryResult.APPLIED);
                }, () -> Optional.of(actuator)),
                Runnable::run
        );

        try {
            // when
            coalescer.onRefreshEvent();

            // then
            assertThat(applyCount).hasValue(1);
            assertThat(appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .filter(event -> event.getFormattedMessage().contains("written by ACTUATOR"))
                    .count()).isEqualTo(1L);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void initialApplyIllegalArgumentExceptionPropagatesWithoutPolicyValueLog() {
        // given
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());
        Logger logger = (Logger) LoggerFactory.getLogger(TracingSettingsRefreshCoalescer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder,
                recordingSettingsWriter((expectedBase, snapshot, writer) -> {
                    throw new IllegalArgumentException("transport rejected value");
                }, readerReturning(1L)),
                Runnable::run
        );

        try {
            // when
            // then
            assertThatThrownBy(coalescer::deliverInitial)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("transport rejected value");
            assertThat(appender.list)
                    .noneMatch(event -> event.getFormattedMessage().contains("policy value invalid"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static String extractAttemptId(String message) {
        int valueStart = message.indexOf("attemptId=") + "attemptId=".length();
        int valueEnd = message.indexOf(',', valueStart);
        return message.substring(valueStart, valueEnd);
    }

    private static SettingsDelivery delivery(TracingSettingsDeliveryResult outcome) {
        String messageCode = switch (outcome) {
            case APPLIED, UNCHANGED -> "NONE";
            case REJECTED -> "INVALID_VALUE";
            case STALE -> "ACCEPTED_SEQUENCE_MISMATCH";
            case FAILED -> "INTERNAL_FAILURE";
        };
        return new SettingsDelivery(
                "00000000-0000-0000-0000-000000000001",
                outcome,
                messageCode
        );
    }
}
