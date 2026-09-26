package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

public final class ObservationCaptureEnvelope {

    private static final String CAPTURE_SEQUENCE = sharedItemName(
            JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_SEQUENCE,
            JmxWireContract.FIELD_SCRUBBING_OBSERVATION_CAPTURE_SEQUENCE,
            JmxWireContract.FIELD_VALIDATION_OBSERVATION_CAPTURE_SEQUENCE,
            JmxWireContract.FIELD_EXPORT_OBSERVATION_CAPTURE_SEQUENCE,
            JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_SEQUENCE,
            JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_SEQUENCE
    );

    private static final String AGENT_INSTANCE_ID = sharedItemName(
            JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID,
            JmxWireContract.FIELD_SCRUBBING_OBSERVATION_AGENT_INSTANCE_ID,
            JmxWireContract.FIELD_VALIDATION_OBSERVATION_AGENT_INSTANCE_ID,
            JmxWireContract.FIELD_EXPORT_OBSERVATION_AGENT_INSTANCE_ID,
            JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_AGENT_INSTANCE_ID,
            JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_AGENT_INSTANCE_ID
    );

    private static final String CAPTURE_STARTED_AT = sharedItemName(
            JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_STARTED_AT,
            JmxWireContract.FIELD_SCRUBBING_OBSERVATION_CAPTURE_STARTED_AT,
            JmxWireContract.FIELD_VALIDATION_OBSERVATION_CAPTURE_STARTED_AT,
            JmxWireContract.FIELD_EXPORT_OBSERVATION_CAPTURE_STARTED_AT,
            JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_STARTED_AT,
            JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_STARTED_AT
    );

    private static final String CAPTURE_COMPLETED_AT = sharedItemName(
            JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_COMPLETED_AT,
            JmxWireContract.FIELD_SCRUBBING_OBSERVATION_CAPTURE_COMPLETED_AT,
            JmxWireContract.FIELD_VALIDATION_OBSERVATION_CAPTURE_COMPLETED_AT,
            JmxWireContract.FIELD_EXPORT_OBSERVATION_CAPTURE_COMPLETED_AT,
            JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_COMPLETED_AT,
            JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_COMPLETED_AT
    );

    private final String agentInstanceId;
    private final AtomicLong captureSequence = new AtomicLong();

    public ObservationCaptureEnvelope(AgentInstanceId agentInstanceId) {
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId").toString();
    }

    public OpenCapture open() {
        return new OpenCapture(captureSequence.incrementAndGet(), Instant.now().toEpochMilli());
    }

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

    public final class OpenCapture {

        private final long sequence;
        private final long startedAtEpochMilli;

        private OpenCapture(long sequence, long startedAtEpochMilli) {
            this.sequence = sequence;
            this.startedAtEpochMilli = startedAtEpochMilli;
        }

        public void sealInto(Map<String, Object> values) {
            Objects.requireNonNull(values, "values");

            values.put(CAPTURE_SEQUENCE, sequence);
            values.put(AGENT_INSTANCE_ID, agentInstanceId);
            values.put(CAPTURE_STARTED_AT, startedAtEpochMilli);
            values.put(CAPTURE_COMPLETED_AT, Instant.now().toEpochMilli());
        }
    }
}
