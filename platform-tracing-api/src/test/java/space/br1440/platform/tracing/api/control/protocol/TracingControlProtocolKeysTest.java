package space.br1440.platform.tracing.api.control.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migrated from {@code schema/TracingControlProtocolKeysTest}.
 *
 * <p>Asserts stability of well-known wire-key string constants.
 *
 * <p>The speculative schema-read operation assertion was removed: that operation
 * was deleted in slice-3 as test-only API.
 * A guard asserting its <em>absence</em> from the operation enum is added
 * instead, so any accidental re-introduction is caught.
 */
@DisplayName("TracingControlProtocolKeys — stable wire constants")
class TracingControlProtocolKeysTest {

    @Test
    @DisplayName("envelope and sampling keys are stable dotted names")
    void keyNamesAreStable() {
        assertThat(TracingControlProtocolKeys.CONTRACT_VERSION)
                .isEqualTo("contractVersion");
        assertThat(TracingControlProtocolKeys.OPERATION)
                .isEqualTo("operation");
        assertThat(TracingControlProtocolKeys.SAMPLING_RATIO)
                .isEqualTo("sampling.ratio");
        assertThat(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS)
                .isEqualTo("sampling.routeRatios");
        assertThat(TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED)
                .isEqualTo("sampling.killSwitch.enabled");
    }

    @Test
    @DisplayName("operation enum is the only operation wire-value source")
    void operationConstants() {
        String removedSchemaReadOperation = "READ_" + "SCHEMA";

        assertThat(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY.wireValue())
                .isEqualTo("APPLY_RUNTIME_POLICY");
        assertThat(TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY.wireValue())
                .isEqualTo("VALIDATE_RUNTIME_POLICY");
        assertThat(TracingControlProtocolOperation.READ_APPLIED_STATE.wireValue())
                .isEqualTo("READ_APPLIED_STATE");

        assertThat(TracingControlProtocolOperation.values())
                .as("operation enum must have exactly 3 values")
                .hasSize(3);
        for (TracingControlProtocolOperation op : TracingControlProtocolOperation.values()) {
            assertThat(op.name())
                    .as("removed schema-read operation must not appear")
                    .isNotEqualTo(removedSchemaReadOperation);
        }
    }

    @Test
    @DisplayName("topology keys are not part of the request key constants")
    void topologyKeysAreNotConstants() {
        String removedPrefix = "TOPOLOGY" + "_";
        assertThat(TracingControlProtocolKeys.class.getFields())
                .extracting(field -> field.getName())
                .noneMatch(name -> name.startsWith(removedPrefix));
    }
}
