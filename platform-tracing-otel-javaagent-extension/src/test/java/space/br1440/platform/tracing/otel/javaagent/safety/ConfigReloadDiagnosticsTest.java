package space.br1440.platform.tracing.otel.javaagent.safety;

import org.junit.jupiter.api.Test;

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
    }

    @Test
    void audit_trail_ограничен_по_размеру() {
        ConfigReloadDiagnostics diag = new ConfigReloadDiagnostics();

        int total = ConfigReloadDiagnostics.AUDIT_CAPACITY + 10;
        for (int i = 0; i < total; i++) {
            diag.record("sampling", true, "JMX", i);
        }

        String[] trail = diag.auditTrail();
        assertThat(trail).hasSize(ConfigReloadDiagnostics.AUDIT_CAPACITY);
        // Старые записи вытеснены: последняя запись содержит максимальную версию.
        assertThat(trail[trail.length - 1]).contains("v=" + (total - 1));
    }
}
