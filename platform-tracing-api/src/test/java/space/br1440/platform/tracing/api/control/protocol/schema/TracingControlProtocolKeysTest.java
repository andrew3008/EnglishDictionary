package space.br1440.platform.tracing.api.control.protocol.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TracingControlProtocolKeys")
class TracingControlProtocolKeysTest {

    @Test
    @DisplayName("envelope and sampling keys are stable dotted names")
    void keyNamesAreStable() {
        assertThat(TracingControlProtocolKeys.CONTRACT_VERSION).isEqualTo("contractVersion");
        assertThat(TracingControlProtocolKeys.OPERATION).isEqualTo("operation");
        assertThat(TracingControlProtocolKeys.SAMPLING_RATIO).isEqualTo("sampling.ratio");
        assertThat(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS).isEqualTo("sampling.routeRatios");
        assertThat(TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED).isEqualTo("sampling.killSwitch.enabled");
    }

    @Test
    @DisplayName("operation constants are uppercase wire verbs")
    void operationConstants() {
        assertThat(TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY).isEqualTo("APPLY_RUNTIME_POLICY");
        assertThat(TracingControlProtocolKeys.OPERATION_VALIDATE_RUNTIME_POLICY).isEqualTo("VALIDATE_RUNTIME_POLICY");
        assertThat(TracingControlProtocolKeys.OPERATION_READ_APPLIED_STATE).isEqualTo("READ_APPLIED_STATE");
        assertThat(TracingControlProtocolKeys.OPERATION_READ_SCHEMA).isEqualTo("READ_SCHEMA");
    }

    @Test
    @DisplayName("topology keys use exporter/sdk prefixes")
    void topologyKeys() {
        assertThat(TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT).isEqualTo("exporter.endpoint");
        assertThat(TracingControlProtocolKeys.TOPOLOGY_SDK_MODE).isEqualTo("sdk.mode");
    }
}
