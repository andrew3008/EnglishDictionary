package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Точечное покрытие {@link JmxReadStateRegistry}: устаревшая доставка не откатывает
 * текущее состояние, lifetime-счётчики не теряются при этом, unrecovered корректно
 * переключается только принятым success, WARN-эпизод открывается один раз.
 */
class JmxReadStateRegistryTest {

    private static final JmxContractPoint POINT = JmxContractPoint.EXPORT_OBSERVATION;
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private final JmxReadStateRegistry registry =
            new JmxReadStateRegistry(Clock.fixed(BASE, java.time.ZoneOffset.UTC), Duration.ofMinutes(5));

    @Test
    void staleFailureDeliveredAfterNewerSuccessDoesNotReopenUnrecovered() {
        // Более новый успех (sequence=11) применяется раньше, чем запоздавший старый отказ (sequence=10).
        registry.recordPresent(POINT, 11L, BASE.plusSeconds(1));
        registry.recordContractViolation(POINT, JmxDiagnosticKind.VALUE_TYPE_MISMATCH, 10L, BASE);

        JmxDiagnosticSnapshot snapshot = onlySnapshot();
        assertThat(snapshot.unrecovered()).isFalse();
        assertThat(snapshot.lifetimeCount()).isEqualTo(1L);
        assertThat(registry.pointState(POINT).lastOutcome()).isEqualTo(JmxLastReadOutcome.PRESENT);
    }

    @Test
    void staleSuccessDeliveredAfterNewerFailureDoesNotClearUnrecovered() {
        registry.recordContractViolation(POINT, JmxDiagnosticKind.VALUE_TYPE_MISMATCH, 20L, BASE.plusSeconds(1));
        registry.recordPresent(POINT, 19L, BASE);

        JmxDiagnosticSnapshot snapshot = onlySnapshot();
        assertThat(snapshot.unrecovered()).isTrue();
        assertThat(registry.pointState(POINT).lastOutcome()).isEqualTo(JmxLastReadOutcome.CONTRACT_VIOLATION);
    }

    @Test
    void lifetimeCountsSurviveOutOfOrderDeliveryEvenWhenCurrentStateIsRejected() {
        registry.recordContractViolation(POINT, JmxDiagnosticKind.VALUE_TYPE_MISMATCH, 5L, BASE);
        registry.recordPresent(POINT, 6L, BASE.plusSeconds(1));
        // Устаревший повторный отказ с меньшим sequence — не должен откатить текущее состояние,
        // но обязан быть учтён в lifetime-счётчике: история отказа реально произошла.
        registry.recordContractViolation(POINT, JmxDiagnosticKind.VALUE_TYPE_MISMATCH, 4L, BASE.minusSeconds(1));

        JmxDiagnosticSnapshot snapshot = onlySnapshot();
        assertThat(snapshot.lifetimeCount()).isEqualTo(2L);
        assertThat(registry.pointState(POINT).lastOutcome()).isEqualTo(JmxLastReadOutcome.PRESENT);
    }

    @Test
    void recoveryThenNewFailureOpensSecondEpisodeAndWarnsAgain() {
        registry.recordContractViolation(POINT, JmxDiagnosticKind.VALUE_TYPE_MISMATCH, 1L, BASE);
        registry.recordPresent(POINT, 2L, BASE.plusSeconds(1));
        registry.recordContractViolation(POINT, JmxDiagnosticKind.VALUE_TYPE_MISMATCH, 3L, BASE.plusSeconds(2));

        JmxDiagnosticSnapshot snapshot = onlySnapshot();
        assertThat(snapshot.unrecovered()).isTrue();
        assertThat(snapshot.lifetimeCount()).isEqualTo(2L);
    }

    @Test
    void diagnosticSnapshotIsEmptyBeforeAnyFailure() {
        registry.recordPresent(POINT, 1L, BASE);

        assertThat(registry.diagnosticSnapshot()).isEmpty();
    }

    private JmxDiagnosticSnapshot onlySnapshot() {
        List<JmxDiagnosticSnapshot> snapshots = registry.diagnosticSnapshot();
        assertThat(snapshots).hasSize(1);
        return snapshots.get(0);
    }
}
