package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;

class ConfigServerAgentReadinessProbeTest {

    private static final String AGENT_INSTANCE_ID = "060130bb-9b7d-4d70-9806-0339966e4231";
    private static final int WEB_SERVER_PHASE = (SmartLifecycle.DEFAULT_PHASE - 2048);

    @Test
    void initialDeliveryAppliedStartsProbe() {
        // given
        RecordingApplier applier = RecordingApplier.always(TracingSettingsDeliveryResult.APPLIED);
        ConfigServerTracingSettingsAutoConfiguration.ConfigServerAgentReadinessProbe probe = newProbe(applier);

        // when
        probe.start();

        // then
        assertThat(probe.isRunning()).isTrue();
        assertThat(applier.applyCalls()).isEqualTo(1);
    }

    @Test
    void initialDeliveryUnchangedStartsProbe() {
        // given
        RecordingApplier applier = RecordingApplier.always(TracingSettingsDeliveryResult.UNCHANGED);
        ConfigServerTracingSettingsAutoConfiguration.ConfigServerAgentReadinessProbe probe = newProbe(applier);

        // when
        probe.start();

        // then
        assertThat(probe.isRunning()).isTrue();
        assertThat(applier.delivered())
                .as("UNCHANGED is a completed delivery of the Config Server manifest, not a skipped one")
                .containsExactly(expectedSnapshot());
    }

    @Test
    void initialDeliveryRejectedBlocksReadinessWithoutRetry() {
        // given
        RecordingApplier applier = RecordingApplier.always(TracingSettingsDeliveryResult.REJECTED);
        ConfigServerTracingSettingsAutoConfiguration.ConfigServerAgentReadinessProbe probe = newProbe(applier);

        // when / then
        assertThatThrownBy(probe::start)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("outcome=REJECTED");
        assertThat(probe.isRunning()).isFalse();
        assertThat(applier.applyCalls())
                .as("REJECTED is a decision, not a race: no retry")
                .isEqualTo(1);
    }

    @Test
    void initialDeliveryFailedBlocksReadinessWithoutRetry() {
        // given
        RecordingApplier applier = RecordingApplier.always(TracingSettingsDeliveryResult.FAILED);
        ConfigServerTracingSettingsAutoConfiguration.ConfigServerAgentReadinessProbe probe = newProbe(applier);

        // when / then
        assertThatThrownBy(probe::start)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("outcome=FAILED");
        assertThat(probe.isRunning()).isFalse();
        assertThat(applier.applyCalls()).isEqualTo(1);
    }

    @Test
    void initialDeliveryStaleRetriesOnceAndStartsProbe() {
        // given
        RecordingApplier applier = RecordingApplier.queued(
                TracingSettingsDeliveryResult.STALE,
                TracingSettingsDeliveryResult.APPLIED);
        ConfigServerTracingSettingsAutoConfiguration.ConfigServerAgentReadinessProbe probe = newProbe(applier);

        // when
        probe.start();

        // then
        assertThat(probe.isRunning())
                .as("STALE is a concurrent-writer race; the bounded retry must be able to start the probe")
                .isTrue();
        assertThat(applier.applyCalls()).isEqualTo(2);
    }

    @Test
    void initialDeliveryStaleTwiceBlocksReadinessAndStaysBounded() {
        // given
        RecordingApplier applier = RecordingApplier.always(TracingSettingsDeliveryResult.STALE);
        ConfigServerTracingSettingsAutoConfiguration.ConfigServerAgentReadinessProbe probe = newProbe(applier);

        // when / then
        assertThatThrownBy(probe::start)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("outcome=STALE");
        assertThat(probe.isRunning()).isFalse();
        assertThat(applier.applyCalls())
                .as("retry is bounded at one: initial attempt plus one retry")
                .isEqualTo(2);
    }

    @Test
    void probeStartsBeforeTheWebServerAcceptsTraffic() {
        // given
        ConfigServerTracingSettingsAutoConfiguration.ConfigServerAgentReadinessProbe probe =
                newProbe(RecordingApplier.always(TracingSettingsDeliveryResult.APPLIED));

        // when
        int phase = probe.getPhase();

        // then
        assertThat(phase)
                .as("a rejected initial policy must block startup before requests can be served")
                .isLessThan(WEB_SERVER_PHASE);
    }

    private static ConfigServerTracingSettingsAutoConfiguration.ConfigServerAgentReadinessProbe newProbe(
            TracingSettingsApplier applier) {
        CompleteTracingSettingsReader reader = readerReturning(expectedSnapshot());
        MockEnvironment environment = authoritativeEnvironment();
        ConfigServerSettingsManifestBuilder manifestBuilder = new ConfigServerSettingsManifestBuilder(
                environment,
                new ConfigServerSettingsProvenanceClassifier(environment));
        TracingSettingsRefreshCoalescer coalescer =
                new TracingSettingsRefreshCoalescer(manifestBuilder, applier, reader, Runnable::run);
        return new ConfigServerTracingSettingsAutoConfiguration.ConfigServerAgentReadinessProbe(reader, coalescer);
    }

    private static CompleteTracingSettingsReader readerReturning(CompleteTracingSettings snapshot) {
        AcceptedSettingsWithSequence accepted =
                new AcceptedSettingsWithSequence(7L, AGENT_INSTANCE_ID, snapshot);
        return () -> Optional.of(accepted);
    }

    private static MockEnvironment authoritativeEnvironment() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("platform.tracing.policy.sampling.enabled", "true");
        values.put("platform.tracing.policy.sampling.ratio", "1.0");
        values.put("platform.tracing.policy.validation.level", "WARNING");
        values.put("platform.tracing.policy.exporter.enabled", "true");
        values.put("platform.tracing.policy.propagation.trace-control-headers.enabled", "true");
        values.put("platform.tracing.policy.diagnostics.log-level", "INFO");
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("configserver:readiness", values));
        return environment;
    }

    private static CompleteTracingSettings expectedSnapshot() {
        return CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0d)
                .samplingRouteRatios(Map.of())
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
    }

    private static final class RecordingApplier implements TracingSettingsApplier {

        private final Deque<TracingSettingsDeliveryResult> queued = new ArrayDeque<>();
        private final List<CompleteTracingSettings> delivered = new ArrayList<>();
        private final TracingSettingsDeliveryResult sticky;

        private RecordingApplier(TracingSettingsDeliveryResult sticky,
                                 List<TracingSettingsDeliveryResult> queued) {
            this.sticky = sticky;
            this.queued.addAll(queued);
        }

        static RecordingApplier always(TracingSettingsDeliveryResult outcome) {
            return new RecordingApplier(outcome, List.of());
        }

        @SuppressWarnings("SameParameterValue")
        static RecordingApplier queued(TracingSettingsDeliveryResult first,
                                       TracingSettingsDeliveryResult second) {
            return new RecordingApplier(second, List.of(first, second));
        }

        @Override
        public TracingSettingsDeliveryResult applySnapshot(ExpectedSettingsRevision expectedBase,
                                                          CompleteTracingSettings snapshot,
                                                          TracingSettingsDeliverySource source) {
            delivered.add(snapshot);
            TracingSettingsDeliveryResult next = queued.poll();
            return (next == null) ? sticky : next;
        }

        int applyCalls() {
            return delivered.size();
        }

        List<CompleteTracingSettings> delivered() {
            return delivered;
        }
    }
}
