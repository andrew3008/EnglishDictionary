package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;

class LastSettingsUpdateSnapshotTest {

    @Test
    void next_afterEmpty_doesNotThrowAndRecordsFirstOutcome() {
        // given
        LastSettingsUpdateSnapshot initial = LastSettingsUpdateSnapshot.empty();
        SettingsUpdateResult applied = result(SettingsUpdateStatus.APPLIED);

        // when
        LastSettingsUpdateSnapshot afterFirstUpdate = initial.next(attempt(1L), applied);

        // then
        assertThat(afterFirstUpdate.countOf(SettingsUpdateStatus.APPLIED)).isEqualTo(1L);
        assertThat(afterFirstUpdate.countOf(SettingsUpdateStatus.REJECTED)).isEqualTo(0L);
        assertThat(afterFirstUpdate.latestOutcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(afterFirstUpdate.latestMessageCode()).isEqualTo(SettingsRejectionReason.NONE);
    }

    @Test
    void constructor_throwsNullPointerException_whenOutcomeCountsIsNull() {
        // when/then
        assertThatThrownBy(() -> new LastSettingsUpdateSnapshot(null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("outcomeCounts");
    }

    @Test
    void next_throwsNullPointerException_whenAttemptOrResultIsNull() {
        // given
        LastSettingsUpdateSnapshot initial = LastSettingsUpdateSnapshot.empty();
        SettingsUpdateResult applied = result(SettingsUpdateStatus.APPLIED);
        SettingsUpdateAttempt attempt = attempt(1L);

        // when/then
        assertThatThrownBy(() -> initial.next(null, applied))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("attempt");
        assertThatThrownBy(() -> initial.next(attempt, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("result");
    }

    @Test
    void outcomeCounts_iterationOrder_matchesEnumDeclarationOrder() {
        // given
        Map<SettingsUpdateStatus, Long> scrambled = new LinkedHashMap<>();
        scrambled.put(SettingsUpdateStatus.FAILED, 5L);
        scrambled.put(SettingsUpdateStatus.REJECTED, 4L);
        scrambled.put(SettingsUpdateStatus.STALE, 3L);
        scrambled.put(SettingsUpdateStatus.UNCHANGED, 2L);
        scrambled.put(SettingsUpdateStatus.APPLIED, 1L);

        LastSettingsUpdateSnapshot snapshot = new LastSettingsUpdateSnapshot(
                attempt(1L),
                SettingsUpdateStatus.FAILED,
                SettingsRejectionReason.INTERNAL_FAILURE,
                scrambled);

        // then
        assertThat(new ArrayList<>(snapshot.outcomeCounts().keySet()))
                .containsExactly(SettingsUpdateStatus.values());
        assertThat(new ArrayList<>(snapshot.outcomeCounts().values()))
                .containsExactly(1L, 2L, 4L, 3L, 5L);
    }

    @Test
    void next_withReverseObservedAt_recordsWithoutFailure_andKeepsLatestTupleCoherent() {
        // given
        LastSettingsUpdateSnapshot initial = LastSettingsUpdateSnapshot.empty();
        SettingsUpdateAttempt earlyObservedButRecordedSecond = attempt(1L);
        SettingsUpdateAttempt lateObservedButRecordedFirst = attempt(9L);
        assertThat(earlyObservedButRecordedSecond.observedAt())
                .isBefore(lateObservedButRecordedFirst.observedAt());

        // when
        LastSettingsUpdateSnapshot afterFirst =
                initial.next(lateObservedButRecordedFirst, result(SettingsUpdateStatus.APPLIED));
        LastSettingsUpdateSnapshot afterSecond =
                afterFirst.next(
                        earlyObservedButRecordedSecond,
                        result(SettingsUpdateStatus.STALE, SettingsRejectionReason.ACCEPTED_SEQUENCE_MISMATCH));

        // then
        assertThat(afterSecond.latestAttempt()).isEqualTo(earlyObservedButRecordedSecond);
        assertThat(afterSecond.latestOutcome()).isEqualTo(SettingsUpdateStatus.STALE);
        assertThat(afterSecond.latestMessageCode())
                .isEqualTo(SettingsRejectionReason.ACCEPTED_SEQUENCE_MISMATCH);
        assertThat(afterSecond.countOf(SettingsUpdateStatus.APPLIED)).isEqualTo(1L);
        assertThat(afterSecond.countOf(SettingsUpdateStatus.STALE)).isEqualTo(1L);
    }

    @Test
    void next_interleavedOutcomes_keepsCountsExact() {
        // when
        LastSettingsUpdateSnapshot snapshot = LastSettingsUpdateSnapshot.empty()
                .next(attempt(1L), result(SettingsUpdateStatus.APPLIED))
                .next(attempt(2L), result(SettingsUpdateStatus.REJECTED))
                .next(attempt(3L), result(SettingsUpdateStatus.APPLIED))
                .next(attempt(4L), result(SettingsUpdateStatus.STALE))
                .next(attempt(5L), result(SettingsUpdateStatus.APPLIED));

        // then
        assertThat(snapshot.countOf(SettingsUpdateStatus.APPLIED)).isEqualTo(3L);
        assertThat(snapshot.countOf(SettingsUpdateStatus.REJECTED)).isEqualTo(1L);
        assertThat(snapshot.countOf(SettingsUpdateStatus.STALE)).isEqualTo(1L);
        assertThat(snapshot.latestAttempt()).isEqualTo(attempt(5L));
        assertThat(snapshot.latestOutcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
    }

    @Test
    void nextWithoutAttempt_clearsLatestAttempt_whileKeepingOutcomeCoherent() {
        // given
        LastSettingsUpdateSnapshot afterApply = LastSettingsUpdateSnapshot.empty()
                .next(attempt(1L), result(SettingsUpdateStatus.APPLIED));

        // when
        SettingsUpdateResult rejected = result(
                SettingsUpdateStatus.REJECTED,
                SettingsRejectionReason.TYPE_MISMATCH);
        LastSettingsUpdateSnapshot afterRejection = afterApply.nextWithoutAttempt(rejected);

        // then
        assertThat(afterRejection.latestAttempt()).isNull();
        assertThat(afterRejection.latestOutcome()).isEqualTo(SettingsUpdateStatus.REJECTED);
        assertThat(afterRejection.latestMessageCode()).isEqualTo(rejected.messageCode());
        assertThat(afterRejection.countOf(SettingsUpdateStatus.APPLIED)).isEqualTo(1L);
        assertThat(afterRejection.countOf(SettingsUpdateStatus.REJECTED)).isEqualTo(1L);
    }

    @Test
    void nextWithoutAttempt_throwsNullPointerException_whenResultIsNull() {
        // given
        LastSettingsUpdateSnapshot initial = LastSettingsUpdateSnapshot.empty();

        // when/then
        assertThatThrownBy(() -> initial.nextWithoutAttempt(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("result");
    }

    private static SettingsUpdateAttempt attempt(long ordinal) {
        return new SettingsUpdateAttempt(
                new UUID(0L, ordinal).toString(),
                Instant.parse("2026-07-28T10:15:30Z").plusSeconds(ordinal),
                SettingsSourceAuthority.CONFIG_SERVER,
                SettingsUpdateTrigger.REFRESH,
                "revision-" + ordinal);
    }

    private static SettingsUpdateResult result(SettingsUpdateStatus outcome) {
        SettingsRejectionReason messageCode = outcome.isSuccessful()
                ? SettingsRejectionReason.NONE
                : SettingsRejectionReason.INTERNAL_FAILURE;
        return result(outcome, messageCode);
    }

    private static SettingsUpdateResult result(SettingsUpdateStatus outcome,
                                               SettingsRejectionReason messageCode) {
        TracingSettings policy = TracingSettings.of(
                true,
                0.5d,
                Map.of(), SpanAttributeValidationLevel.WARNING, true, true, TracingLogLevel.INFO);
        TracingSettingsStore store = new TracingSettingsStore(
                "00000000-0000-0000-0000-000000000001",
                policy);
        TracingSettingsRevision accepted = store.current();
        return new SettingsUpdateResult(
                outcome,
                accepted,
                TracingSettingsDigest.sha256(policy),
                messageCode);
    }
}
