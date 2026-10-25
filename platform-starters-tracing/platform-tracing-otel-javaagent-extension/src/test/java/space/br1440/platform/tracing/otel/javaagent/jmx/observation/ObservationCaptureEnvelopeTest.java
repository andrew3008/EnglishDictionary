package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

class ObservationCaptureEnvelopeTest {

    @Test
    void putWritesOnlyAgentInstanceId() {
        // given
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        Map<String, Object> values = new LinkedHashMap<>();

        // when
        new ObservationCaptureEnvelope(agentInstanceId).putInto(values);

        // then
        assertThat(values).containsExactlyEntriesOf(
                Map.of(
                        JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID,
                        agentInstanceId.toString()
                )
        );
    }

    @Test
    void constructorRejectsMissingAgentInstanceId() {
        // when
        // then
        assertThatThrownBy(() -> new ObservationCaptureEnvelope(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("agentInstanceId");
    }

    @Test
    void putRejectsMissingValues() {
        // when
        // then
        assertThatThrownBy(() -> new ObservationCaptureEnvelope(AgentInstanceId.random()).putInto(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("values");
    }

    @Test
    void agentInstanceIdItemNameIsSharedAcrossContracts() {
        // when
        // then
        assertThat(List.of(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID,
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_AGENT_INSTANCE_ID,
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_AGENT_INSTANCE_ID,
                JmxWireContract.FIELD_EXPORT_OBSERVATION_AGENT_INSTANCE_ID,
                JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_AGENT_INSTANCE_ID,
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_AGENT_INSTANCE_ID))
                .containsOnly(JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID);
    }
}
