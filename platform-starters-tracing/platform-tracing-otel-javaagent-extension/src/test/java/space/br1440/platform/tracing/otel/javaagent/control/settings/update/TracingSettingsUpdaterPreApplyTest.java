package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

class TracingSettingsUpdaterPreApplyTest {

    private TracingSettingsStore store;
    private TracingSettingsUpdater updater;

    @BeforeEach
    void setUp() {
        store = AcceptedPolicyTestFixtures.bootstrapAgentDefaultsStore(
                new AgentInstanceId(UUID.randomUUID()),
                TracingSettings.of(
                        true,
                        1.0d,
                        Map.of(),
                        SpanAttributeValidationLevel.WARNING,
                        true,
                        true,
                        AgentLogLevel.INFO
                )
        );
        updater = new TracingSettingsUpdater(store, level -> { });
    }

    @Test
    void rejectBeforeApplyDoesNotCommit() {
        // given
        TracingSettingsRevision before = store.current();

        // when
        SettingsUpdateResult result = updater.rejectBeforeApply(SettingsRejectionReason.TYPE_MISMATCH);

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.REJECTED);
        assertThat(result.messageCode()).isEqualTo(SettingsRejectionReason.TYPE_MISMATCH);
        assertThat(result.accepted()).isSameAs(before);
        assertThat(store.current()).isSameAs(before);
    }

    @Test
    void failBeforeApplyDoesNotCommit() {
        // given
        TracingSettingsRevision before = store.current();

        // when
        SettingsUpdateResult result = updater.failBeforeApply(SettingsRejectionReason.INTERNAL_FAILURE);

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.FAILED);
        assertThat(result.messageCode()).isEqualTo(SettingsRejectionReason.INTERNAL_FAILURE);
        assertThat(result.accepted()).isSameAs(before);
        assertThat(store.current()).isSameAs(before);
    }
}
