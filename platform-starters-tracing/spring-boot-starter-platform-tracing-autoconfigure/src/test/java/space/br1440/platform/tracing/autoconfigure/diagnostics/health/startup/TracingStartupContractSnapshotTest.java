package space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Snapshot должен быть valid-by-construction: любая комбинация полей, не соответствующая
 * заявленному {@link TracingStartupContractOutcome}, отклоняется компактным конструктором.
 */
class TracingStartupContractSnapshotTest {

    @Test
    void enabledContractSatisfiedAcceptsOnlyExactCombination() {
        TracingStartupContractSnapshot snapshot = new TracingStartupContractSnapshot(
                true,
                TracingStartupContractSnapshot.WIRE_RUNTIME_MODE_ENABLED,
                TracingStartupContractSnapshot.WIRE_AGENT_STATE_READY,
                "",
                TracingStartupContractOutcome.ENABLED_CONTRACT_SATISFIED);

        assertThat(snapshot.failureCode()).isEmpty();
    }

    @Test
    void disabledContractSatisfiedAcceptsOnlyExactCombination() {
        TracingStartupContractSnapshot snapshot = new TracingStartupContractSnapshot(
                false,
                TracingStartupContractSnapshot.WIRE_RUNTIME_MODE_DISABLED_BY_CONFIGURATION,
                TracingStartupContractSnapshot.WIRE_AGENT_STATE_DISABLED,
                "",
                TracingStartupContractOutcome.DISABLED_CONTRACT_SATISFIED);

        assertThat(snapshot.failureCode()).isEmpty();
    }

    @Test
    void violatedOutcomeFillsBlankFailureCodeWithContextPreservingConstant() {
        TracingStartupContractSnapshot snapshot = new TracingStartupContractSnapshot(
                true,
                "EXTENSION_FAILED",
                "EXTENSION_FAILED",
                "",
                TracingStartupContractOutcome.STARTUP_CONTRACT_VIOLATED);

        assertThat(snapshot.failureCode())
                .isEqualTo(TracingStartupContractSnapshot.WIRE_FAILURE_CODE_UNSPECIFIED_VIOLATION);
    }

    @Test
    void violatedOutcomePreservesExplicitFailureCode() {
        TracingStartupContractSnapshot snapshot = new TracingStartupContractSnapshot(
                true,
                "EXTENSION_FAILED",
                "EXTENSION_FAILED",
                "TEST_AGENT_FAILURE",
                TracingStartupContractOutcome.STARTUP_CONTRACT_VIOLATED);

        assertThat(snapshot.failureCode()).isEqualTo("TEST_AGENT_FAILURE");
    }

    @Test
    void enabledContractRejectsDisabledTracingFlag() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TracingStartupContractSnapshot(
                false,
                TracingStartupContractSnapshot.WIRE_RUNTIME_MODE_ENABLED,
                TracingStartupContractSnapshot.WIRE_AGENT_STATE_READY,
                "",
                TracingStartupContractOutcome.ENABLED_CONTRACT_SATISFIED));
    }

    @Test
    void enabledContractRejectsDisabledRuntimeMode() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TracingStartupContractSnapshot(
                true,
                TracingStartupContractSnapshot.WIRE_RUNTIME_MODE_DISABLED_BY_CONFIGURATION,
                TracingStartupContractSnapshot.WIRE_AGENT_STATE_READY,
                "",
                TracingStartupContractOutcome.ENABLED_CONTRACT_SATISFIED));
    }

    @Test
    void enabledContractRejectsNonReadyAgentState() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TracingStartupContractSnapshot(
                true,
                TracingStartupContractSnapshot.WIRE_RUNTIME_MODE_ENABLED,
                TracingStartupContractSnapshot.WIRE_AGENT_STATE_DISABLED,
                "",
                TracingStartupContractOutcome.ENABLED_CONTRACT_SATISFIED));
    }

    @Test
    void enabledContractRejectsNonBlankFailureCode() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TracingStartupContractSnapshot(
                true,
                TracingStartupContractSnapshot.WIRE_RUNTIME_MODE_ENABLED,
                TracingStartupContractSnapshot.WIRE_AGENT_STATE_READY,
                "UNEXPECTED",
                TracingStartupContractOutcome.ENABLED_CONTRACT_SATISFIED));
    }

    @Test
    void disabledContractRejectsEnabledTracingFlag() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TracingStartupContractSnapshot(
                true,
                TracingStartupContractSnapshot.WIRE_RUNTIME_MODE_DISABLED_BY_CONFIGURATION,
                TracingStartupContractSnapshot.WIRE_AGENT_STATE_DISABLED,
                "",
                TracingStartupContractOutcome.DISABLED_CONTRACT_SATISFIED));
    }

    @Test
    void disabledContractRejectsReadyAgentState() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TracingStartupContractSnapshot(
                false,
                TracingStartupContractSnapshot.WIRE_RUNTIME_MODE_DISABLED_BY_CONFIGURATION,
                TracingStartupContractSnapshot.WIRE_AGENT_STATE_READY,
                "",
                TracingStartupContractOutcome.DISABLED_CONTRACT_SATISFIED));
    }

    @Test
    void rejectsNullRuntimeMode() {
        assertThatNullPointerException().isThrownBy(() -> new TracingStartupContractSnapshot(
                true, null, "AGENT_READY", "", TracingStartupContractOutcome.ENABLED_CONTRACT_SATISFIED));
    }
}
