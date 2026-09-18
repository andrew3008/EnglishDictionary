package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TracingSettingsUpdaterPreApplyTest {

    private TracingSettingsStore store;
    private LastSettingsUpdate diagnostics;
    private TracingSettingsUpdater updater;

    @BeforeEach
    void setUp() {
        store = new TracingSettingsStore(
                UUID.randomUUID().toString(),
                TracingSettings.of(true, 1.0, Map.of(), SpanAttributeValidationLevel.WARNING, true, true, TracingLogLevel.INFO));
        diagnostics = new LastSettingsUpdate();
        updater = new TracingSettingsUpdater(store, diagnostics);
    }

    @Test
    void rejectBeforeApply_withCanonicalAttemptId_recordsAttemptAndDoesNotCommit() {
        // given
        TracingSettingsRevision before = store.current();
        String attemptId = UUID.randomUUID().toString();

        // when
        SettingsUpdateResult result = updater.rejectBeforeApply(
                attemptId,
                SettingsRejectionReason.TYPE_MISMATCH);

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.REJECTED);
        assertThat(result.messageCode()).isEqualTo(SettingsRejectionReason.TYPE_MISMATCH);
        assertThat(store.current()).isSameAs(before);
        assertThat(diagnostics.snapshot().latestAttempt().attemptId()).isEqualTo(attemptId);
        assertThat(diagnostics.snapshot().latestOutcome()).isEqualTo(SettingsUpdateStatus.REJECTED);
        assertThat(diagnostics.snapshot().latestMessageCode())
                .isEqualTo(SettingsRejectionReason.TYPE_MISMATCH);
    }

    @Test
    void rejectBeforeApply_withEmptyAttemptId_leavesLatestAttemptNull() {
        // when
        SettingsUpdateResult result = updater.rejectBeforeApply(
                "",
                SettingsRejectionReason.MISSING_REQUIRED_FIELD);

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.REJECTED);
        assertThat(diagnostics.snapshot().latestAttempt()).isNull();
        assertThat(diagnostics.snapshot().latestOutcome()).isEqualTo(SettingsUpdateStatus.REJECTED);
        assertThat(diagnostics.snapshot().latestMessageCode())
                .isEqualTo(SettingsRejectionReason.MISSING_REQUIRED_FIELD);
    }

    @Test
    void failBeforeApply_recordsFailedWithoutCommit() {
        // given
        TracingSettingsRevision before = store.current();

        // when
        SettingsUpdateResult result = updater.failBeforeApply(
                "not-a-uuid",
                SettingsRejectionReason.INTERNAL_FAILURE);

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.FAILED);
        assertThat(result.messageCode()).isEqualTo(SettingsRejectionReason.INTERNAL_FAILURE);
        assertThat(store.current()).isSameAs(before);
        assertThat(diagnostics.snapshot().latestAttempt()).isNull();
        assertThat(diagnostics.snapshot().latestOutcome()).isEqualTo(SettingsUpdateStatus.FAILED);
    }
}
