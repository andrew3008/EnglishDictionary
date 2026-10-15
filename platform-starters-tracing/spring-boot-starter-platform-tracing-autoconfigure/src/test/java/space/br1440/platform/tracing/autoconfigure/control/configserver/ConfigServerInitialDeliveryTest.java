package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter.recordingSettingsWriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.support.AcceptedSettingsTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter;

class ConfigServerInitialDeliveryTest {

    private static final String AGENT_INSTANCE_ID = "060130bb-9b7d-4d70-9806-0339966e4231";
    private static final String ATTEMPT_ID = "00000000-0000-0000-0000-000000000001";
    private static final int WEB_SERVER_PHASE = (SmartLifecycle.DEFAULT_PHASE - 2048);

    @Test
    void initialDeliveryAppliedMarksRunning() {
        // given
        RecordingApplier applier = RecordingApplier.always(TracingSettingsDeliveryResult.APPLIED);
        ConfigServerInitialDelivery delivery = newDelivery(applier);

        // when
        delivery.start();

        // then
        assertThat(delivery.isRunning()).isTrue();
        assertThat(applier.applyCalls()).isEqualTo(1);
    }

    @Test
    void initialDeliveryUnchangedMarksRunning() {
        // given
        RecordingApplier applier = RecordingApplier.always(TracingSettingsDeliveryResult.UNCHANGED);
        ConfigServerInitialDelivery delivery = newDelivery(applier);

        // when
        delivery.start();

        // then
        assertThat(delivery.isRunning()).isTrue();
        assertThat(applier.delivered())
                .as("UNCHANGED is a completed delivery of the Config Server manifest, not a skipped one")
                .containsExactly(expectedSnapshot());
    }

    @Test
    void initialDeliveryRejectedBlocksStartupWithoutRetry() {
        // given
        RecordingApplier applier = RecordingApplier.always(TracingSettingsDeliveryResult.REJECTED);
        ConfigServerInitialDelivery delivery = newDelivery(applier);

        // when / then
        assertThatThrownBy(delivery::start)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining(
                        "outcome=REJECTED, attemptId=%s, messageCode=INVALID_VALUE".formatted(ATTEMPT_ID));
        assertThat(delivery.isRunning()).isFalse();
        assertThat(applier.applyCalls())
                .as("REJECTED is a decision, not a race: no retry")
                .isEqualTo(1);
    }

    @Test
    void initialDeliveryFailedBlocksStartupWithoutRetry() {
        // given
        RecordingApplier applier = RecordingApplier.always(TracingSettingsDeliveryResult.FAILED);
        ConfigServerInitialDelivery delivery = newDelivery(applier);

        // when / then
        assertThatThrownBy(delivery::start)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining(
                        "outcome=FAILED, attemptId=%s, messageCode=INTERNAL_FAILURE".formatted(ATTEMPT_ID));
        assertThat(delivery.isRunning()).isFalse();
        assertThat(applier.applyCalls()).isEqualTo(1);
    }

    @Test
    void initialDeliveryStaleBlocksStartupWithoutRetry() {
        // given
        RecordingApplier applier = RecordingApplier.always(TracingSettingsDeliveryResult.STALE);
        ConfigServerInitialDelivery delivery = newDelivery(applier);

        // when / then
        assertThatThrownBy(delivery::start)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("outcome=STALE, attemptId=%s, messageCode=ACCEPTED_SEQUENCE_MISMATCH"
                        .formatted(ATTEMPT_ID));
        assertThat(delivery.isRunning()).isFalse();
        assertThat(applier.applyCalls()).isEqualTo(1);
    }

    @Test
    void emptyAcceptedSettingsFailInsideDeliverInitial() {
        // given
        ConfigServerInitialDelivery delivery = newDelivery(
                RecordingApplier.always(TracingSettingsDeliveryResult.APPLIED),
                Optional::empty
        );

        // when / then
        assertThatThrownBy(delivery::start)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("[CONFIG_SERVER INITIAL] accepted settings are unreadable");
        assertThat(delivery.isRunning()).isFalse();
    }

    @Test
    void invalidManifestFailsBeforeAcceptedSettingsAreRead() {
        // given
        AtomicBoolean read = new AtomicBoolean();
        MockEnvironment environment = new MockEnvironment();
        ConfigServerSettingsManifestBuilder manifestBuilder = new ConfigServerSettingsManifestBuilder(environment);
        RecordingSettingsWriter writer = recordingSettingsWriter(
                RecordingApplier.always(TracingSettingsDeliveryResult.APPLIED),
                () -> {
                    read.set(true);
                    return Optional.empty();
                });
        ConfigServerInitialDelivery delivery = new ConfigServerInitialDelivery(
                new ConfigServerTracingSettingsSynchronizer(manifestBuilder, writer));

        // when / then
        assertThatThrownBy(delivery::start)
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("Required policy key missing");
        assertThat(read).isFalse();
        assertThat(delivery.isRunning()).isFalse();
    }

    @Test
    void initialDeliveryPhasePrecedesWebServer() {
        // given
        ConfigServerInitialDelivery delivery = newDelivery(
                RecordingApplier.always(TracingSettingsDeliveryResult.APPLIED)
        );

        // when
        int phase = delivery.getPhase();

        // then
        assertThat(phase).isEqualTo(ConfigServerInitialDelivery.PHASE);
        assertThat(phase)
                .as("a rejected initial policy must block startup before requests can be served")
                .isLessThan(WEB_SERVER_PHASE);
    }

    private static ConfigServerInitialDelivery newDelivery(RecordingSettingsWriter.ApplyBehavior applyBehavior) {
        return newDelivery(applyBehavior, readerReturning(expectedSnapshot()));
    }

    private static ConfigServerInitialDelivery newDelivery(RecordingSettingsWriter.ApplyBehavior applyBehavior,
                                                           Supplier<Optional<AcceptedSettings>> reader) {
        RecordingSettingsWriter writer = recordingSettingsWriter(applyBehavior, reader);
        MockEnvironment environment = authoritativeEnvironment();
        ConfigServerSettingsManifestBuilder manifestBuilder = new ConfigServerSettingsManifestBuilder(environment);
        ConfigServerTracingSettingsSynchronizer synchronizer =
                new ConfigServerTracingSettingsSynchronizer(manifestBuilder, writer);
        return new ConfigServerInitialDelivery(synchronizer);
    }

    private static Supplier<Optional<AcceptedSettings>> readerReturning(CompleteTracingSettings snapshot) {
        AcceptedSettings accepted = AcceptedSettingsTestFixtures.bootstrapAgentDefaults(
                7L,
                AGENT_INSTANCE_ID,
                snapshot
        );
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

    private static final class RecordingApplier implements RecordingSettingsWriter.ApplyBehavior {

        private final List<CompleteTracingSettings> delivered = new ArrayList<>();
        private final TracingSettingsDeliveryResult outcome;

        private RecordingApplier(TracingSettingsDeliveryResult outcome) {
            this.outcome = outcome;
        }

        static RecordingApplier always(TracingSettingsDeliveryResult outcome) {
            return new RecordingApplier(outcome);
        }

        @Override
        public SettingsDelivery applySnapshot(SettingsRevision expectedBase,
                                              CompleteTracingSettings snapshot,
                                              SettingsWriter writer) {
            delivered.add(snapshot);
            String messageCode = switch (outcome) {
                case APPLIED, UNCHANGED -> "NONE";
                case REJECTED -> "INVALID_VALUE";
                case STALE -> "ACCEPTED_SEQUENCE_MISMATCH";
                case FAILED -> "INTERNAL_FAILURE";
            };

            return new SettingsDelivery(ATTEMPT_ID, outcome, messageCode);
        }

        int applyCalls() {
            return delivered.size();
        }

        List<CompleteTracingSettings> delivered() {
            return delivered;
        }
    }
}
