package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт {@link ConfigReloadDiagnostics} (Фаза 14): счётчики applied/rejected, последний
 * источник/время и bounded audit-trail.
 */
class ConfigReloadDiagnosticsTest {

    @Test
    void считает_applied_и_rejected_и_фиксирует_последний_источник() {
        ConfigReloadDiagnostics diag = new ConfigReloadDiagnostics();

        diag.record("sampling", true, "JMX", 5);
        diag.record("scrubbing", false, "JMX", 2);

        assertThat(diag.getUpdatesApplied()).isEqualTo(1);
        assertThat(diag.getUpdatesRejected()).isEqualTo(1);
        assertThat(diag.getLastDomain()).isEqualTo("scrubbing");
        assertThat(diag.getLastSource()).isEqualTo("JMX");
        assertThat(diag.getLastUpdateEpochMs()).isPositive();

        assertThat(diag.snapshot())
                .containsEntry("updates.applied", 1L)
                .containsEntry("updates.rejected", 1L)
                .containsKey("last_update.epoch_ms");

        List<ConfigReloadDiagnostics.AuditEntry> trail = diag.auditTrail();
        assertThat(trail).hasSize(2);
        assertThat(trail.get(0).domain()).isEqualTo("sampling");
        assertThat(trail.get(0).applied()).isTrue();
        assertThat(trail.get(0).source()).isEqualTo("JMX");
        assertThat(trail.get(0).version()).isEqualTo(5L);
        assertThat(trail.get(1).domain()).isEqualTo("scrubbing");
        assertThat(trail.get(1).applied()).isFalse();
        assertThat(trail.get(1).version()).isEqualTo(2L);
    }

    @Test
    void audit_trail_ограничен_по_размеру() {
        ConfigReloadDiagnostics diag = new ConfigReloadDiagnostics();

        int total = ConfigReloadDiagnostics.AUDIT_CAPACITY + 10;
        for (int i = 0; i < total; i++) {
            diag.record("sampling", true, "JMX", i);
        }

        List<ConfigReloadDiagnostics.AuditEntry> trail = diag.auditTrail();
        assertThat(trail).hasSize(ConfigReloadDiagnostics.AUDIT_CAPACITY);
        assertThat(trail.getFirst().version()).isEqualTo(10L);
        assertThat(trail.getLast().version()).isEqualTo(total - 1L);
    }

    @Test
    void audit_entry_toString_matches_legacy_wire_format() {
        ConfigReloadDiagnostics.AuditEntry entry =
                new ConfigReloadDiagnostics.AuditEntry(1_700_000_000_000L, "sampling", true, "JMX", 7);

        assertThat(entry.toString()).isEqualTo("1700000000000 sampling applied source=JMX v=7");
    }
}
