package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LastSettingsUpdateSnapshotTest {

    @Test
    void next_afterEmpty_doesNotThrowAndRecordsFirstOutcome() {
        LastSettingsUpdateSnapshot initial = LastSettingsUpdateSnapshot.empty();
        SettingsUpdateResult applied = result(SettingsUpdateStatus.APPLIED);

        LastSettingsUpdateSnapshot afterFirstUpdate = initial.next(attempt(1L), applied);

        assertThat(afterFirstUpdate.countOf(SettingsUpdateStatus.APPLIED)).isEqualTo(1L);
        assertThat(afterFirstUpdate.countOf(SettingsUpdateStatus.REJECTED)).isEqualTo(0L);
        assertThat(afterFirstUpdate.latestOutcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(afterFirstUpdate.latestMessageCode()).isEqualTo(SettingsRejectionReason.NONE);
    }

    @Test
    void constructor_throwsNullPointerException_whenOutcomeCountsIsNull() {
        assertThatThrownBy(() -> new LastSettingsUpdateSnapshot(
                null,
                null,
                null,
                LastSettingsUpdateSnapshot.Timestamps.empty(),
                null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("outcomeCounts");
    }

    @Test
    void next_throwsNullPointerException_whenAttemptOrResultIsNull() {
        LastSettingsUpdateSnapshot initial = LastSettingsUpdateSnapshot.empty();
        SettingsUpdateResult applied = result(SettingsUpdateStatus.APPLIED);
        SettingsUpdateAttempt attempt = attempt(1L);

        assertThatThrownBy(() -> initial.next(null, applied))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("attempt");
        assertThatThrownBy(() -> initial.next(attempt, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("result");
    }

    @Test
    void outcomeCounts_iterationOrder_matchesEnumDeclarationOrder() {
        // Вставляем в порядке, обратном объявлению enum — compact constructor обязан
        // нормализовать в EnumMap, иначе JMX/golden wire-parity получит нестабильный порядок.
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
                LastSettingsUpdateSnapshot.Timestamps.empty(),
                scrambled);

        // Порядок объявления: APPLIED, UNCHANGED, REJECTED, STALE, FAILED.
        assertThat(new ArrayList<>(snapshot.outcomeCounts().keySet()))
                .containsExactly(SettingsUpdateStatus.values());
        assertThat(new ArrayList<>(snapshot.outcomeCounts().values()))
                .containsExactly(1L, 2L, 4L, 3L, 5L);
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
        TracingSettings policy = TracingSettings.of(
                true,
                0.5d,
                Map.of(),
                true,
                false,
                true,
                true,
                TracingLogLevel.INFO);
        TracingSettingsStore store = new TracingSettingsStore(
                "00000000-0000-0000-0000-000000000001",
                policy);
        TracingSettingsRevision accepted = store.current();
        SettingsRejectionReason messageCode = outcome.isSuccessful()
                ? SettingsRejectionReason.NONE
                : SettingsRejectionReason.INTERNAL_FAILURE;
        return new SettingsUpdateResult(
                outcome,
                accepted,
                TracingSettingsDigest.sha256(policy),
                messageCode);
    }
}
