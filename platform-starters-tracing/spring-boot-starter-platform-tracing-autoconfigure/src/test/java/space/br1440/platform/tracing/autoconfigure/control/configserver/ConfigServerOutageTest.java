package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter.recordingSettingsWriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.support.AcceptedSettingsTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;
import space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ConfigServerOutageTest {

    private static final String AGENT_INSTANCE_ID = "872314d1-579d-440b-9694-847b3963b7b0";

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

    private static Supplier<Optional<AcceptedSettings>> readerWith(long sequence) {
        AcceptedSettings snap =
                AcceptedSettingsTestFixtures.configServerAccepted(
                        sequence,
                        AGENT_INSTANCE_ID,
                        sampleSnapshot()
                );
        return () -> Optional.of(snap);
    }

    private static Supplier<Optional<AcceptedSettings>> advancingReader(AtomicLong seq) {
        return () -> Optional.of(
                AcceptedSettingsTestFixtures.configServerAccepted(
                        seq.get(),
                        AGENT_INSTANCE_ID,
                        sampleSnapshot()
                ));
    }

    @Test
    void initial_missing_leaf_propagates_exception_and_blocks_readiness() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenThrow(
                new TracingSettingsManifestException("Required Category A policy key missing: "
                        + "'platform.tracing.policy.sampling.enabled'"));

        AtomicInteger applyCount = new AtomicInteger(0);
        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, s, writer) -> {
            applyCount.incrementAndGet();
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, readerWith(1L)), Runnable::run);

        assertThatThrownBy(coalescer::deliverInitial)
                .as("missing leaf MUST propagate TracingSettingsManifestException, blocking readiness")
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("platform.tracing.policy.sampling.enabled");

        assertThat(applyCount.get())
                .as("applySnapshot MUST NOT be called when manifest is incomplete")
                .isEqualTo(0);
    }

    @Test
    void initial_local_override_leaf_propagates_exception_and_blocks_readiness() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenThrow(
                new TracingSettingsManifestException(
                        "Local override rejected for policy key "
                                + "'platform.tracing.policy.sampling.ratio'"));

        AtomicInteger applyCount = new AtomicInteger(0);
        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, s, writer) -> {
            applyCount.incrementAndGet();
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, readerWith(1L)), Runnable::run);

        assertThatThrownBy(coalescer::deliverInitial)
                .as("local override MUST propagate TracingSettingsManifestException")
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("Local override rejected");

        assertThat(applyCount.get()).isEqualTo(0);
    }

    @Test
    void initial_unknown_provenance_propagates_exception_and_blocks_readiness() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenThrow(
                new TracingSettingsManifestException(
                        "Unknown property source for key "
                                + "'platform.tracing.policy.exporter.enabled'"));

        AtomicInteger applyCount = new AtomicInteger(0);
        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, s, writer) -> {
            applyCount.incrementAndGet();
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, readerWith(1L)), Runnable::run);

        assertThatThrownBy(coalescer::deliverInitial)
                .as("unknown provenance MUST propagate TracingSettingsManifestException")
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("Unknown property source");

        assertThat(applyCount.get()).isEqualTo(0);
    }

    @Test
    void initial_rejected_outcome_returned_to_caller_and_no_state_established() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger applyCount = new AtomicInteger(0);
        RecordingSettingsWriter.ApplyBehavior rejectingClient = (expectedBase, s, writer) -> {
            applyCount.incrementAndGet();
            return delivery(TracingSettingsDeliveryResult.REJECTED);
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(rejectingClient, readerWith(1L)), Runnable::run);

        SettingsDelivery result = coalescer.deliverInitial();

        assertThat(result.outcome())
                .as("REJECTED outcome MUST be returned to caller so readiness can be blocked")
                .isEqualTo(TracingSettingsDeliveryResult.REJECTED);

        assertThat(applyCount.get())
                .as("exactly one apply attempt MUST have been made")
                .isEqualTo(1);
    }

    @Test
    void initial_failed_outcome_returned_to_caller_and_no_state_established() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        RecordingSettingsWriter.ApplyBehavior failingClient = (expectedBase, s, writer) ->
                delivery(TracingSettingsDeliveryResult.FAILED);

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(failingClient, readerWith(1L)), Runnable::run);

        SettingsDelivery result = coalescer.deliverInitial();

        assertThat(result.outcome())
                .as("FAILED outcome MUST be returned to caller so readiness can be blocked")
                .isEqualTo(TracingSettingsDeliveryResult.FAILED);
    }

    @Test
    void initial_writer_throws_exception_propagates_out_of_deliver_initial() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        RecordingSettingsWriter.ApplyBehavior throwingClient = (expectedBase, s, writer) -> {
            throw new RuntimeException("JMX transport unavailable");
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(throwingClient, readerWith(1L)), Runnable::run);

        assertThatThrownBy(coalescer::deliverInitial)
                .as("writer exception MUST propagate; deliverInitial() must NOT swallow it")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("JMX transport unavailable");
    }

    @Test
    void runtime_manifest_build_failure_skips_apply_and_retains_lkg() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);

        // First call (initial): succeeds.
        when(builder.build())
                .thenReturn(sampleSnapshot())
                .thenThrow(new TracingSettingsManifestException(
                        "Config Server connection lost; required key missing"));

        AtomicLong capturedSeq = new AtomicLong(-99L);
        AtomicInteger applyCount = new AtomicInteger(0);
        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, s, writer) -> {
            capturedSeq.set(expectedBase.acceptedSequence());
            applyCount.incrementAndGet();
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };

        AtomicLong agentSeq = new AtomicLong(3L);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, advancingReader(agentSeq)), Runnable::run);

        assertThat(coalescer.deliverInitial().outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        assertThat(applyCount.get()).isEqualTo(1);
        long seqAfterInitial = capturedSeq.get();
        assertThat(seqAfterInitial).isEqualTo(3L);

        coalescer.onRefreshEvent();

        assertThat(applyCount.get())
                .as("applySnapshot MUST NOT be called when runtime manifest build fails")
                .isEqualTo(1); // no second call
    }

    @Test
    void runtime_local_override_detected_skips_apply_and_retains_lkg() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build())
                .thenReturn(sampleSnapshot())
                .thenThrow(new TracingSettingsManifestException(
                        "Local override rejected for policy key "
                                + "'platform.tracing.policy.validation.level'"));

        AtomicInteger applyCount = new AtomicInteger(0);
        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, s, writer) -> {
            applyCount.incrementAndGet();
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, readerWith(2L)), Runnable::run);

        coalescer.deliverInitial();
        assertThat(applyCount.get()).isEqualTo(1);

        coalescer.onRefreshEvent(); // triggers outage

        assertThat(applyCount.get())
                .as("local override during runtime refresh MUST skip apply")
                .isEqualTo(1);
    }

    @Test
    void runtime_unknown_provenance_skips_apply_and_retains_lkg() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build())
                .thenReturn(sampleSnapshot())
                .thenThrow(new TracingSettingsManifestException(
                        "Unknown property source for key "
                                + "'platform.tracing.policy.diagnostics.log-level'"));

        AtomicInteger applyCount = new AtomicInteger(0);
        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, s, writer) -> {
            applyCount.incrementAndGet();
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, readerWith(4L)), Runnable::run);

        coalescer.deliverInitial();
        coalescer.onRefreshEvent();

        assertThat(applyCount.get())
                .as("unknown provenance during runtime refresh MUST skip apply")
                .isEqualTo(1);
    }

    @Test
    void runtime_rejected_retains_lkg_and_does_not_update_sequence() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger callN = new AtomicInteger(0);
        List<Long> seenSeqs = new ArrayList<>();

        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, s, writer) -> {
            seenSeqs.add(expectedBase.acceptedSequence());
            if (callN.incrementAndGet() == 1) return delivery(TracingSettingsDeliveryResult.APPLIED);
            return delivery(TracingSettingsDeliveryResult.REJECTED);
        };

        AtomicLong agentSeq = new AtomicLong(5L);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, advancingReader(agentSeq)), Runnable::run);

        coalescer.deliverInitial();
        agentSeq.set(6L);

        coalescer.onRefreshEvent();
        coalescer.onRefreshEvent();

        assertThat(callN.get())
                .as("exactly 3 apply calls: 1 initial + 2 runtime")
                .isEqualTo(3);

        assertThat(seenSeqs.get(0)).isEqualTo(5L);
        assertThat(seenSeqs.get(1))
                .as("each attempt uses the agent revision read at that attempt")
                .isEqualTo(6L);
        assertThat(seenSeqs.get(2)).isEqualTo(6L);
    }

    @Test
    void runtime_failed_retains_lkg_and_does_not_update_sequence() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger callN = new AtomicInteger(0);
        List<Long> seenSeqs = new ArrayList<>();

        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, s, writer) -> {
            seenSeqs.add(expectedBase.acceptedSequence());
            if (callN.incrementAndGet() == 1) return delivery(TracingSettingsDeliveryResult.APPLIED);
            return delivery(TracingSettingsDeliveryResult.FAILED);
        };

        AtomicLong agentSeq = new AtomicLong(10L);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, advancingReader(agentSeq)), Runnable::run);

        coalescer.deliverInitial();
        agentSeq.set(11L);

        coalescer.onRefreshEvent();
        coalescer.onRefreshEvent();

        assertThat(callN.get()).isEqualTo(3);

        assertThat(seenSeqs.get(0)).isEqualTo(10L);
        assertThat(seenSeqs.get(1))
                .as("each attempt uses the agent revision read at that attempt")
                .isEqualTo(11L);
        assertThat(seenSeqs.get(2)).isEqualTo(11L);
    }

    @Test
    void multiple_sequential_runtime_outages_sequence_stable_throughout() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build())
                .thenReturn(sampleSnapshot())
                .thenThrow(new TracingSettingsManifestException("outage 1"))
                .thenThrow(new TracingSettingsManifestException("outage 2"))
                .thenThrow(new TracingSettingsManifestException("outage 3"));

        AtomicInteger applyCount = new AtomicInteger(0);
        List<Long> seenSeqs = new ArrayList<>();
        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, s, writer) -> {
            seenSeqs.add(expectedBase.acceptedSequence());
            applyCount.incrementAndGet();
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, readerWith(7L)), Runnable::run);

        coalescer.deliverInitial();
        coalescer.onRefreshEvent();
        coalescer.onRefreshEvent();
        coalescer.onRefreshEvent();

        assertThat(applyCount.get())
                .as("only the initial apply must have occurred; 3 runtime outages skip apply")
                .isEqualTo(1);
        assertThat(seenSeqs)
                .as("only one sequence observation from the successful initial delivery")
                .containsExactly(7L);
    }

    @Test
    void successful_refresh_after_outage_advances_sequence_normally() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build())
                .thenReturn(sampleSnapshot())
                .thenThrow(new TracingSettingsManifestException("transient outage"))
                .thenReturn(sampleSnapshot());

        List<Long> seenSeqs = new ArrayList<>();
        AtomicInteger applyCount = new AtomicInteger(0);
        RecordingSettingsWriter.ApplyBehavior client = (expectedBase, s, writer) -> {
            seenSeqs.add(expectedBase.acceptedSequence());
            applyCount.incrementAndGet();
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };

        AtomicLong agentSeq = new AtomicLong(2L);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(client, advancingReader(agentSeq)), Runnable::run
        );

        coalescer.deliverInitial();
        agentSeq.set(3L);

        coalescer.onRefreshEvent();
        coalescer.onRefreshEvent();

        assertThat(applyCount.get())
                .as("exactly 2 apply calls: initial + post-outage recovery")
                .isEqualTo(2);
        assertThat(seenSeqs)
                .as("recovery reads the agent revision current at that attempt")
                .containsExactly(2L, 3L);

        coalescer.onRefreshEvent();
        assertThat(applyCount.get()).isEqualTo(3);
        assertThat(seenSeqs.get(2)).isEqualTo(3L);
    }

    @Test
    void e2e_runtime_outage_after_initial_success_does_not_degrade_agent_sequence() throws Exception {
        try (FakeAgentPlatformJmxPlane agent =
                     FakeAgentPlatformJmxPlane.compliant(AGENT_INSTANCE_ID, 0L)) {

            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build())
                    .thenReturn(sampleSnapshot())
                    .thenThrow(new TracingSettingsManifestException("e2e outage"));

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, Runnable::run);

            SettingsDelivery initial = coalescer.deliverInitial();
            assertThat(initial.outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);

            long seqAfterInitial = agent.currentAcceptedSequence();
            assertThat(seqAfterInitial)
                    .as("sequence must have advanced after initial APPLIED")
                    .isGreaterThan(0L);

            coalescer.onRefreshEvent();

            assertThat(agent.currentAcceptedSequence())
                    .as("runtime outage MUST NOT degrade Agent sequence below post-initial value")
                    .isEqualTo(seqAfterInitial);
        }
    }

    @Test
    void e2e_initial_rejected_leaves_agent_sequence_at_baseline() throws Exception {
        try (FakeAgentPlatformJmxPlane agent =
                     FakeAgentPlatformJmxPlane.compliant(AGENT_INSTANCE_ID, 5L)) {

            agent.setNextApplyOutcome("REJECTED");
            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, Runnable::run);

            SettingsDelivery result = coalescer.deliverInitial();

            assertThat(result.outcome())
                    .as("REJECTED must be returned to caller")
                    .isEqualTo(TracingSettingsDeliveryResult.REJECTED);

            assertThat(agent.currentAcceptedSequence())
                    .as("REJECTED MUST NOT increment Agent sequence")
                    .isEqualTo(5L);
        }
    }

    @Test
    void e2e_multiple_runtime_outages_agent_sequence_stable() throws Exception {
        try (FakeAgentPlatformJmxPlane agent =
                     FakeAgentPlatformJmxPlane.compliant(AGENT_INSTANCE_ID, 1L)) {

            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build())
                    .thenReturn(sampleSnapshot())
                    .thenThrow(new TracingSettingsManifestException("outage A"))
                    .thenThrow(new TracingSettingsManifestException("outage B"))
                    .thenThrow(new TracingSettingsManifestException("outage C"));

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, Runnable::run);

            coalescer.deliverInitial();
            long seqAfterInitial = agent.currentAcceptedSequence();

            coalescer.onRefreshEvent();
            coalescer.onRefreshEvent();
            coalescer.onRefreshEvent();

            assertThat(agent.currentAcceptedSequence())
                    .as("3 consecutive runtime outages MUST leave Agent sequence unchanged")
                    .isEqualTo(seqAfterInitial);
        }
    }

    @Test
    void e2e_recovery_after_multiple_outages_applies_to_agent() throws Exception {
        try (FakeAgentPlatformJmxPlane agent =
                     FakeAgentPlatformJmxPlane.compliant(AGENT_INSTANCE_ID, 10L)) {

            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build())
                    .thenReturn(sampleSnapshot())                                          // initial
                    .thenThrow(new TracingSettingsManifestException("outage 1"))              // refresh 1
                    .thenThrow(new TracingSettingsManifestException("outage 2"))              // refresh 2
                    .thenReturn(sampleSnapshot());                                          // refresh 3 (recovery)

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, Runnable::run);

            coalescer.deliverInitial();
            long seqAfterInitial = agent.currentAcceptedSequence();

            coalescer.onRefreshEvent(); // outage
            coalescer.onRefreshEvent(); // outage
            coalescer.onRefreshEvent(); // recovery

            assertThat(agent.currentAcceptedSequence())
                    .as("recovery after outages MUST advance Agent sequence beyond post-initial value")
                    .isGreaterThan(seqAfterInitial);
        }
    }

    @Test
    void adapter_non_policy_keys_do_not_trigger_coalescer() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger refreshCount = new AtomicInteger(0);
        RecordingSettingsWriter.ApplyBehavior countingClient = (expectedBase, s, writer) -> {
            refreshCount.incrementAndGet();
            return delivery(TracingSettingsDeliveryResult.APPLIED);
        };

        // Wrap coalescer in a counting proxy to detect onRefreshEvent() calls.
        AtomicInteger onRefreshCalls = new AtomicInteger(0);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, recordingSettingsWriter(countingClient, readerWith(1L)), task -> {
            onRefreshCalls.incrementAndGet();
            task.run();
        });

        ConfigServerTracingSettingsAdapter adapter = new ConfigServerTracingSettingsAdapter(coalescer);

        // Fire events with NON-policy keys only.
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(
                Set.of("spring.datasource.url", "management.endpoints.enabled")));
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(
                Set.of("logging.level.root")));
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(
                Set.of("platform.tracing.javaagent.sampling.ratio")));  // NOT policy.*

        assertThat(onRefreshCalls.get())
                .as("non-policy EnvironmentChangeEvent keys MUST NOT trigger coalescer")
                .isEqualTo(0);
    }

    @Test
    void adapter_policy_keys_trigger_coalescer_exactly_once_per_event() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger onRefreshCalls = new AtomicInteger(0);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder,
                recordingSettingsWriter((expectedBase, s, writer) ->
                        delivery(TracingSettingsDeliveryResult.APPLIED), readerWith(1L)),
                task -> {
                    onRefreshCalls.incrementAndGet();
                    task.run();
                });

        ConfigServerTracingSettingsAdapter adapter = new ConfigServerTracingSettingsAdapter(coalescer);

        // Single event with one policy key.
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(
                Set.of("platform.tracing.policy.sampling.enabled")));
        assertThat(onRefreshCalls.get())
                .as("one policy key in event → exactly one coalescer trigger")
                .isEqualTo(1);

        // Single event with multiple policy keys.
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(
                Set.of(
                        "platform.tracing.policy.sampling.ratio",
                        "platform.tracing.policy.validation.level",
                        "platform.tracing.policy.exporter.enabled")));
        assertThat(onRefreshCalls.get())
                .as("multiple policy keys in one event → still exactly one more coalescer trigger")
                .isEqualTo(2);

        // Mixed event: policy + non-policy keys together.
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(
                Set.of(
                        "spring.cloud.config.revision",               // non-policy
                        "platform.tracing.policy.diagnostics.log-level")));
        assertThat(onRefreshCalls.get())
                .as("mixed event with at least one policy key → exactly one more coalescer trigger")
                .isEqualTo(3);
    }

    @Test
    void adapter_empty_key_set_does_not_trigger_coalescer() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger onRefreshCalls = new AtomicInteger(0);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder,
                recordingSettingsWriter((expectedBase, s, writer) ->
                        delivery(TracingSettingsDeliveryResult.APPLIED), readerWith(1L)),
                task -> {
                    onRefreshCalls.incrementAndGet();
                    task.run();
                });

        ConfigServerTracingSettingsAdapter adapter = new ConfigServerTracingSettingsAdapter(coalescer);
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(Set.of()));

        assertThat(onRefreshCalls.get())
                .as("empty EnvironmentChangeEvent MUST NOT trigger coalescer")
                .isEqualTo(0);
    }

    @Test
    void unconvertibleSamplingRatioFailsStartupAndRefreshKeepsLastKnownGood() {
        // given
        Map<String, Object> values = authoritativePolicy();
        MockEnvironment environment = configServerEnvironment(values);

        // when / then
        assertStartupRejectsAndRefreshKeepsLastKnownGood(
                environment,
                () -> values.put("platform.tracing.policy.sampling.ratio", "abc"),
                "'platform.tracing.policy.sampling.ratio'");
    }

    @Test
    void emptySamplingRatioFailsStartupAndRefreshKeepsLastKnownGood() {
        // given
        Map<String, Object> values = authoritativePolicy();
        MockEnvironment environment = configServerEnvironment(values);

        // when / then
        assertStartupRejectsAndRefreshKeepsLastKnownGood(
                environment,
                () -> values.put("platform.tracing.policy.sampling.ratio", ""),
                "'platform.tracing.policy.sampling.ratio'");
    }

    @Test
    void unconvertibleRouteRatioFailsStartupAndRefreshKeepsLastKnownGood() {
        // given
        Map<String, Object> values = authoritativePolicy();
        MockEnvironment environment = configServerEnvironment(values);
        String routeKey = "platform.tracing.policy.sampling.route-ratios[/orders]";

        // when / then
        assertStartupRejectsAndRefreshKeepsLastKnownGood(
                environment,
                () -> values.put(routeKey, "abc"),
                "'" + routeKey + "'");
    }

    @Test
    void environmentRouteRatioRejectsTheManifest() {
        // given
        Map<String, Object> values = authoritativePolicy();
        MockEnvironment environment = configServerEnvironment(values);
        String variable = "PLATFORM_TRACING_POLICY_SAMPLING_ROUTE_RATIOS__ORDERS";

        // when / then
        assertStartupRejectsAndRefreshKeepsLastKnownGood(
                environment,
                () -> environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                        "systemEnvironment",
                        Map.of(variable, "0.5"))),
                "'" + variable + "'");
    }

    @Test
    void agentSamplingRouteRatioEnvironmentVariableDoesNotRejectTheManifest() {
        // given
        MockEnvironment environment = configServerEnvironment(authoritativePolicy());
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "systemEnvironment",
                Map.of("PLATFORM_TRACING_SAMPLING_ROUTE_RATIOS", "/orders=0.5")));
        ConfigServerSettingsManifestBuilder builder = new ConfigServerSettingsManifestBuilder(
                environment,
                new ConfigServerSettingsProvenanceClassifier(environment));

        // when
        CompleteTracingSettings settings = builder.build();

        // then
        assertThat(settings.samplingRatio()).isEqualTo(1.0d);
        assertThat(settings.samplingRouteRatios()).isEmpty();
    }

    private static void assertStartupRejectsAndRefreshKeepsLastKnownGood(MockEnvironment environment,
                                                                          Runnable corrupt,
                                                                          String messageFragment) {
        ConfigServerSettingsManifestBuilder builder = new ConfigServerSettingsManifestBuilder(
                environment,
                new ConfigServerSettingsProvenanceClassifier(environment)
        );

        AtomicInteger applyCount = new AtomicInteger();
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder,
                recordingSettingsWriter((expectedBase, snapshot, writer) -> {
                    applyCount.incrementAndGet();
                    return delivery(TracingSettingsDeliveryResult.APPLIED);
                }, readerWith(1L)),
                Runnable::run);

        Logger logger = (Logger) LoggerFactory.getLogger(TracingSettingsRefreshCoalescer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            corrupt.run();
            assertThatThrownBy(coalescer::deliverInitial)
                    .isInstanceOf(TracingSettingsManifestException.class)
                    .hasMessageContaining(messageFragment);
            assertThat(applyCount.get()).isZero();

            restoreAuthoritativePolicy(environment);
            assertThat(coalescer.deliverInitial().outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
            assertThat(applyCount.get()).isEqualTo(1);

            corrupt.run();
            coalescer.onRefreshEvent();
            assertThat(applyCount.get()).isEqualTo(1);

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(logs)
                    .contains("Config Server manifest rejected locally")
                    .contains(messageFragment);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static Map<String, Object> authoritativePolicy() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("platform.tracing.policy.sampling.enabled", "true");
        values.put("platform.tracing.policy.sampling.ratio", "1.0");
        values.put("platform.tracing.policy.validation.level", "WARNING");
        values.put("platform.tracing.policy.exporter.enabled", "true");
        values.put("platform.tracing.policy.propagation.trace-control-headers.enabled", "true");
        values.put("platform.tracing.policy.diagnostics.log-level", "INFO");
        return values;
    }

    private static MockEnvironment configServerEnvironment(Map<String, Object> values) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("configserver:outage", values));
        return environment;
    }

    private static void restoreAuthoritativePolicy(MockEnvironment environment) {
        while (!"configserver:outage".equals(environment.getPropertySources().iterator().next().getName())) {
            environment.getPropertySources().remove(environment.getPropertySources().iterator().next().getName());
        }

        PropertySource<?> propertySource = environment.getPropertySources().get("configserver:outage");
        if (!(propertySource instanceof MapPropertySource source)) {
            throw new IllegalStateException("Expected MapPropertySource 'configserver:outage'");
        }

        Map<String, Object> values = source.getSource();
        values.clear();
        values.putAll(authoritativePolicy());
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
