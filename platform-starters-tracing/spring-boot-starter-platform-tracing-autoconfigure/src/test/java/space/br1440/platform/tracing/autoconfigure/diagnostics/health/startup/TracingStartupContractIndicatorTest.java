package space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Матрица трёх достижимых outcome. Ветка {@code STARTUP_CONTRACT_VIOLATED} конструируется напрямую
 * через snapshot — без попытки поднять невозможный (fail-closed отклонённый раньше в цепочке)
 * Spring-контекст, недостижимый в штатной работе.
 */
class TracingStartupContractIndicatorTest {

    @Test
    void enabledContractSatisfiedIsUpWithoutFailureCode() {
        TracingStartupContractSnapshot snapshot = new TracingStartupContractSnapshot(
                true,
                TracingStartupContractSnapshot.WIRE_RUNTIME_MODE_ENABLED,
                TracingStartupContractSnapshot.WIRE_AGENT_STATE_READY,
                "",
                TracingStartupContractOutcome.ENABLED_CONTRACT_SATISFIED);

        Health health = new TracingStartupContractIndicator(snapshot).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("reasonCode", "ENABLED_CONTRACT_SATISFIED")
                .containsEntry("tracingEnabled", true)
                .containsEntry("platformRuntimeMode", "ENABLED")
                .containsEntry("agentState", "AGENT_READY")
                .doesNotContainKey("failureCode");
    }

    @Test
    void disabledContractSatisfiedIsUpWithoutFailureCode() {
        TracingStartupContractSnapshot snapshot = new TracingStartupContractSnapshot(
                false,
                TracingStartupContractSnapshot.WIRE_RUNTIME_MODE_DISABLED_BY_CONFIGURATION,
                TracingStartupContractSnapshot.WIRE_AGENT_STATE_DISABLED,
                "",
                TracingStartupContractOutcome.DISABLED_CONTRACT_SATISFIED);

        Health health = new TracingStartupContractIndicator(snapshot).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("reasonCode", "DISABLED_CONTRACT_SATISFIED")
                .containsEntry("tracingEnabled", false)
                .containsEntry("platformRuntimeMode", "DISABLED_BY_CONFIGURATION")
                .containsEntry("agentState", "DISABLED")
                .doesNotContainKey("failureCode");
    }

    @Test
    void violatedContractIsDownWithFailureCode() {
        TracingStartupContractSnapshot snapshot = new TracingStartupContractSnapshot(
                true,
                "EXTENSION_FAILED",
                "EXTENSION_FAILED",
                "TEST_AGENT_FAILURE",
                TracingStartupContractOutcome.STARTUP_CONTRACT_VIOLATED);

        Health health = new TracingStartupContractIndicator(snapshot).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("reasonCode", "STARTUP_CONTRACT_VIOLATED")
                .containsEntry("agentState", "EXTENSION_FAILED")
                .containsEntry("failureCode", "TEST_AGENT_FAILURE");
    }

    @Test
    void repeatedHealthChecksAreIdempotent() {
        TracingStartupContractSnapshot snapshot = new TracingStartupContractSnapshot(
                true,
                TracingStartupContractSnapshot.WIRE_RUNTIME_MODE_ENABLED,
                TracingStartupContractSnapshot.WIRE_AGENT_STATE_READY,
                "",
                TracingStartupContractOutcome.ENABLED_CONTRACT_SATISFIED);
        TracingStartupContractIndicator indicator = new TracingStartupContractIndicator(snapshot);

        assertThat(indicator.health()).isEqualTo(indicator.health());
    }
}
