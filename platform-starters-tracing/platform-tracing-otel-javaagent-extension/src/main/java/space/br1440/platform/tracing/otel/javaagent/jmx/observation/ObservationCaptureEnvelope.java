package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import java.util.Map;
import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

public final class ObservationCaptureEnvelope {

    private static final String AGENT_INSTANCE_ID = sharedItemName(
            JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID,
            JmxWireContract.FIELD_SCRUBBING_OBSERVATION_AGENT_INSTANCE_ID,
            JmxWireContract.FIELD_VALIDATION_OBSERVATION_AGENT_INSTANCE_ID,
            JmxWireContract.FIELD_EXPORT_OBSERVATION_AGENT_INSTANCE_ID,
            JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_AGENT_INSTANCE_ID,
            JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_AGENT_INSTANCE_ID
    );

    private final String agentInstanceId;

    public ObservationCaptureEnvelope(AgentInstanceId agentInstanceId) {
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId").toString();
    }

    public void putInto(Map<String, Object> values) {
        Objects.requireNonNull(values, "values").put(AGENT_INSTANCE_ID, agentInstanceId);
    }

    @SuppressWarnings("SameParameterValue")
    private static String sharedItemName(String first, String second, String third,
                                         String fourth, String fifth, String sixth) {
        if (first.equals(second)
                && first.equals(third)
                && first.equals(fourth)
                && first.equals(fifth)
                && first.equals(sixth)) {
            return first;
        }

        throw new IllegalStateException("observation envelope item names must be shared");
    }
}
