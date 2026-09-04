package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

class ObservationCaptureEnvelopeTest {

    @Test
    void captureSequenceIsMonotonicPerEnvelopeAndStartsAtOne() {
        ObservationCaptureEnvelope envelope = new ObservationCaptureEnvelope(UUID.randomUUID());

        assertThat(List.of(
                sealedSequence(envelope),
                sealedSequence(envelope),
                sealedSequence(envelope))
        ).containsExactly(1L, 2L, 3L);
    }

    @Test
    void envelopesKeepIndependentSequences() {
        UUID agentInstanceId = UUID.randomUUID();
        ObservationCaptureEnvelope first = new ObservationCaptureEnvelope(agentInstanceId);
        ObservationCaptureEnvelope second = new ObservationCaptureEnvelope(agentInstanceId);

        assertThat(sealedSequence(first)).isEqualTo(1L);
        assertThat(sealedSequence(second)).isEqualTo(1L);
        assertThat(sealedSequence(first)).isEqualTo(2L);
    }

    @Test
    void sealWritesSharedEnvelopeFields() {
        UUID agentInstanceId = UUID.randomUUID();
        Map<String, Object> values = new LinkedHashMap<>();
        new ObservationCaptureEnvelope(agentInstanceId).open().sealInto(values);

        assertThat(values.keySet()).containsExactly(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_SEQUENCE,
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID,
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_STARTED_AT,
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_COMPLETED_AT);
        assertThat(values.get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_SEQUENCE)).isEqualTo(1L);
        assertThat(values.get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID))
                .isEqualTo(agentInstanceId.toString());
        long startedAt = (Long) values.get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_STARTED_AT);
        long completedAt = (Long) values.get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_COMPLETED_AT);
        assertThat(completedAt).isGreaterThanOrEqualTo(startedAt);
    }

    @Test
    void completionIsMeasuredAtSealTimeNotAtOpenTime() throws InterruptedException {
        ObservationCaptureEnvelope.OpenCapture openCapture = new ObservationCaptureEnvelope(UUID.randomUUID()).open();
        Thread.sleep(10L);

        Map<String, Object> values = new LinkedHashMap<>();
        openCapture.sealInto(values);

        assertThat((Long) values.get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_COMPLETED_AT))
                .isGreaterThan((Long) values.get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_STARTED_AT));
    }

    @Test
    void constructorRejectsMissingAgentInstanceId() {
        assertThatThrownBy(() -> new ObservationCaptureEnvelope(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("agentInstanceId");
    }

    @Test
    void sealRejectsMissingValues() {
        assertThatThrownBy(() -> new ObservationCaptureEnvelope(UUID.randomUUID()).open().sealInto(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("values");
    }

    @Test
    void observationEnvelopeItemNamesAreSharedAcrossContracts() {
        assertThat(List.of(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_SEQUENCE,
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_CAPTURE_SEQUENCE,
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_CAPTURE_SEQUENCE,
                JmxWireContract.FIELD_EXPORT_OBSERVATION_CAPTURE_SEQUENCE,
                JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_SEQUENCE,
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_SEQUENCE))
                .containsOnly(JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_SEQUENCE);

        assertThat(List.of(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID,
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_AGENT_INSTANCE_ID,
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_AGENT_INSTANCE_ID,
                JmxWireContract.FIELD_EXPORT_OBSERVATION_AGENT_INSTANCE_ID,
                JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_AGENT_INSTANCE_ID,
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_AGENT_INSTANCE_ID))
                .containsOnly(JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID);

        assertThat(List.of(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_STARTED_AT,
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_CAPTURE_STARTED_AT,
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_CAPTURE_STARTED_AT,
                JmxWireContract.FIELD_EXPORT_OBSERVATION_CAPTURE_STARTED_AT,
                JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_STARTED_AT,
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_STARTED_AT))
                .containsOnly(JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_STARTED_AT);

        assertThat(List.of(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_COMPLETED_AT,
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_CAPTURE_COMPLETED_AT,
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_CAPTURE_COMPLETED_AT,
                JmxWireContract.FIELD_EXPORT_OBSERVATION_CAPTURE_COMPLETED_AT,
                JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_COMPLETED_AT,
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_COMPLETED_AT))
                .containsOnly(JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_COMPLETED_AT);
    }

    private static Object sealedSequence(ObservationCaptureEnvelope envelope) {
        Map<String, Object> values = new LinkedHashMap<>();
        envelope.open().sealInto(values);
        return values.get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_SEQUENCE);
    }
}
