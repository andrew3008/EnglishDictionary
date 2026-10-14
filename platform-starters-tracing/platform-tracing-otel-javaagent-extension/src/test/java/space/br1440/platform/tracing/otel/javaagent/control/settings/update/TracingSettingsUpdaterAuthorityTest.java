package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

class TracingSettingsUpdaterAuthorityTest {

    private static final AgentInstanceId AGENT_INSTANCE_ID = AgentInstanceId.parse("00000000-0000-0000-0000-000000000101");

    private TracingSettingsStore store;
    private TracingSettingsUpdater updater;
    private TracingSettings initial;

    @BeforeEach
    void setUp() {
        initial = settings(0.25d, true);
        store = new TracingSettingsStore(AGENT_INSTANCE_ID, initial);
        updater = new TracingSettingsUpdater(store, level -> { });
    }

    @Test
    void configServerCommitsSameValuesWhenAuthorityChanges() {
        // given
        TracingSettingsRevision before = store.current();

        // when
        SettingsUpdateResult result = updater.apply(
                attempt(SettingsWriter.CONFIG_SERVER, 1L),
                before.base(),
                initial
        );

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(result.accepted().acceptedSequence()).isEqualTo(1L);
        assertThat(result.accepted().sourceAuthority()).isEqualTo(SettingsSourceAuthority.CONFIG_SERVER);
    }

    @Test
    void repeatedConfigServerDeliveryWithSameAuthorityIsUnchanged() {
        // given
        SettingsUpdateResult applied = updater.apply(
                attempt(SettingsWriter.CONFIG_SERVER, 1L),
                store.current().base(),
                initial
        );

        // when
        SettingsUpdateResult repeated = updater.apply(
                attempt(SettingsWriter.CONFIG_SERVER, 2L),
                applied.accepted().base(),
                initial
        );

        // then
        assertThat(repeated.outcome()).isEqualTo(SettingsUpdateStatus.UNCHANGED);
        assertThat(repeated.accepted()).isSameAs(applied.accepted());
        assertThat(repeated.accepted().acceptedSequence()).isEqualTo(1L);
    }

    @Test
    void actuatorAppliesCandidateThatChangesOneLeaf() {
        // given
        SettingsUpdateResult configServer = updater.apply(
                attempt(SettingsWriter.CONFIG_SERVER, 1L),
                store.current().base(),
                initial
        );
        TracingSettings candidate = settings(0.75d, true);

        // when
        SettingsUpdateResult result = updater.apply(
                attempt(SettingsWriter.ACTUATOR, 2L),
                configServer.accepted().base(),
                candidate
        );

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(result.accepted().acceptedSequence()).isEqualTo(2L);
        assertThat(result.accepted().sourceAuthority()).isEqualTo(SettingsSourceAuthority.ACTUATOR);
        assertThat(result.accepted().samplingRatio()).isEqualTo(0.75d);
    }

    @Test
    void actuatorSameValueWriteChangesAuthorityAndSequence() {
        // given
        SettingsUpdateResult configServer = updater.apply(
                attempt(SettingsWriter.CONFIG_SERVER, 1L),
                store.current().base(),
                initial
        );

        // when
        SettingsUpdateResult applied = updater.apply(
                attempt(SettingsWriter.ACTUATOR, 2L),
                configServer.accepted().base(),
                initial
        );
        SettingsUpdateResult repeated = updater.apply(
                attempt(SettingsWriter.ACTUATOR, 3L),
                applied.accepted().base(),
                initial
        );

        // then
        assertThat(applied.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(applied.accepted().acceptedSequence()).isEqualTo(2L);
        assertThat(applied.accepted().sourceAuthority()).isEqualTo(SettingsSourceAuthority.ACTUATOR);
        assertThat(repeated.outcome()).isEqualTo(SettingsUpdateStatus.UNCHANGED);
        assertThat(repeated.accepted()).isSameAs(applied.accepted());
    }

    @Test
    void actuatorEmptyDiffOverBootstrapIsApplied() {
        // given
        TracingSettingsRevision before = store.current();

        // when
        SettingsUpdateResult result = updater.apply(
                attempt(SettingsWriter.ACTUATOR, 1L),
                before.base(),
                initial
        );

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(result.accepted().acceptedSequence()).isEqualTo(1L);
        assertThat(result.accepted().sourceAuthority()).isEqualTo(SettingsSourceAuthority.ACTUATOR);
    }

    @Test
    void actuatorRejectsCandidateThatChangesTwoLeaves() {
        // given
        TracingSettingsRevision before = store.current();
        TracingSettings candidate = settings(0.75d, false);

        // when
        SettingsUpdateResult result = updater.apply(
                attempt(SettingsWriter.ACTUATOR, 1L),
                before.base(),
                candidate
        );

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.REJECTED);
        assertThat(result.messageCode()).isEqualTo(SettingsRejectionReason.INVALID_VALUE);
        assertThat(result.accepted()).isSameAs(before);
        assertThat(store.current()).isSameAs(before);
    }

    @Test
    void nonAppliedOutcomesDoNotChangeAcceptedMetadata() {
        // given
        TracingSettingsRevision accepted = store.current();
        SettingsRevision staleBase = new SettingsRevision(
                AGENT_INSTANCE_ID,
                accepted.acceptedSequence() + 1L
        );

        // when
        SettingsUpdateResult stale = updater.apply(
                attempt(SettingsWriter.ACTUATOR, 1L),
                staleBase,
                settings(0.5d, true)
        );
        SettingsUpdateResult rejected = updater.rejectBeforeApply(SettingsRejectionReason.INVALID_VALUE);
        SettingsUpdateResult failed = updater.failBeforeApply(SettingsRejectionReason.INTERNAL_FAILURE);

        // then
        assertThat(stale.outcome()).isEqualTo(SettingsUpdateStatus.STALE);
        assertThat(rejected.outcome()).isEqualTo(SettingsUpdateStatus.REJECTED);
        assertThat(failed.outcome()).isEqualTo(SettingsUpdateStatus.FAILED);
        assertThat(stale.accepted()).isSameAs(accepted);
        assertThat(rejected.accepted()).isSameAs(accepted);
        assertThat(failed.accepted()).isSameAs(accepted);
        assertThat(store.current()).isSameAs(accepted);
    }

    private static SettingsUpdateAttempt attempt(SettingsWriter writer, long ordinal) {
        return new SettingsUpdateAttempt(
                new UUID(0L, ordinal).toString(),
                writer
        );
    }

    private static TracingSettings settings(double samplingRatio, boolean exporterEnabled) {
        return TracingSettings.of(
                true,
                samplingRatio,
                Map.of("/orders", 0.5d),
                SpanAttributeValidationLevel.WARNING,
                exporterEnabled,
                true,
                AgentLogLevel.INFO
        );
    }
}
