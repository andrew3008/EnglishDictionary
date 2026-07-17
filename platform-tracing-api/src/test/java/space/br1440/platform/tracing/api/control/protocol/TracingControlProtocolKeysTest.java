package space.br1440.platform.tracing.api.control.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migrated from {@code schema/TracingControlProtocolKeysTest}.
 *
 * <p>Asserts stability of well-known wire-key string constants.
 *
 * <p>{@code OPERATION_READ_SCHEMA} assertion was removed: that operation
 * was deleted in slice-3 as speculative test-only API.
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
    @DisplayName("operation constants match exactly 3 TracingControlProtocolOperation values; READ_SCHEMA absent")
    void operationConstants() {
        assertThat(TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY)
                .isEqualTo("APPLY_RUNTIME_POLICY");
        assertThat(TracingControlProtocolKeys.OPERATION_VALIDATE_RUNTIME_POLICY)
                .isEqualTo("VALIDATE_RUNTIME_POLICY");
        assertThat(TracingControlProtocolKeys.OPERATION_READ_APPLIED_STATE)
                .isEqualTo("READ_APPLIED_STATE");

        // Guard: READ_SCHEMA must not be present in the enum (deleted in slice-3).
        assertThat(TracingControlProtocolOperation.values())
                .as("operation enum must have exactly 3 values")
                .hasSize(3);
        for (TracingControlProtocolOperation op : TracingControlProtocolOperation.values()) {
            assertThat(op.name())
                    .as("READ_SCHEMA must not appear as an operation")
                    .isNotEqualTo("READ_SCHEMA");
        }
    }

    @Test
    @DisplayName("topology keys use exporter/sdk prefixes")
    void topologyKeys() {
        assertThat(TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT)
                .isEqualTo("exporter.endpoint");
        assertThat(TracingControlProtocolKeys.TOPOLOGY_SDK_MODE)
                .isEqualTo("sdk.mode");
    }
}
