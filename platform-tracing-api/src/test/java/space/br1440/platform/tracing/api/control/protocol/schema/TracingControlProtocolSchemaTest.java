package space.br1440.platform.tracing.api.control.protocol.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TracingControlProtocolSchema")
class TracingControlProtocolSchemaTest {

    @Test
    @DisplayName("current schema exposes contract version 1")
    void contractVersion() {
        assertThat(TracingControlProtocol.current().schema().contractVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("known keys include envelope, policy, topology, and diagnostics")
    void knownKeys() {
        TracingControlProtocolSchema schema = TracingControlProtocol.current().schema();

        assertThat(schema.knownKeys())
                .contains(
                        TracingControlProtocolKeys.CONTRACT_VERSION,
                        TracingControlProtocolKeys.SAMPLING_RATIO,
                        TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT,
                        TracingControlProtocolKeys.DIAGNOSTICS_REQUEST_ID);
    }

    @Test
    @DisplayName("known keys count is 26")
    void knownKeysCount() {
        assertThat(TracingControlProtocol.current().schema().knownKeys()).hasSize(26);
    }

    @Test
    @DisplayName("topology vs runtime policy classification")
    void topologyVsPolicyClassification() {
        TracingControlProtocolSchema schema = TracingControlProtocol.current().schema();

        assertThat(schema.isTopologyKey(TracingControlProtocolKeys.TOPOLOGY_QUEUE_SIZE)).isTrue();
        assertThat(schema.isRuntimePolicyKey(TracingControlProtocolKeys.SCRUBBING_ENABLED)).isTrue();
        assertThat(schema.isRuntimePolicyKey(TracingControlProtocolKeys.TOPOLOGY_SDK_MODE)).isFalse();
        assertThat(schema.isDiagnosticKey(TracingControlProtocolKeys.DIAGNOSTICS_TIMESTAMP)).isTrue();
        assertThat(schema.isEnvelopeKey(TracingControlProtocolKeys.SOURCE)).isTrue();
    }

    @Test
    @DisplayName("field descriptors are immutable value objects")
    void fieldDescriptor() {
        TracingControlProtocolFieldDescriptor descriptor = TracingControlProtocol.current().schema()
                .descriptorOf(TracingControlProtocolKeys.SAMPLING_RATIO);

        assertThat(descriptor.key()).isEqualTo(TracingControlProtocolKeys.SAMPLING_RATIO);
        assertThat(descriptor.type()).isEqualTo(TracingControlProtocolTypes.DOUBLE);
        assertThat(descriptor.category()).isEqualTo(TracingControlProtocolFieldCategory.RUNTIME_POLICY);
    }
}
