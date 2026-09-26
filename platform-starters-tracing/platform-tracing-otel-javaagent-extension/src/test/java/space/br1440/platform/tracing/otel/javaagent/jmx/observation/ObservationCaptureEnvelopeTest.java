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
    void captureSequenceIsMonotonicPerEnvelopeAndStartsAtOne() {
        // given
        ObservationCaptureEnvelope envelope = new ObservationCaptureEnvelope(AgentInstanceId.random());

        // when/then
        assertThat(List.of(
                sealedSequence(envelope),
                sealedSequence(envelope),
                sealedSequence(envelope))
        ).containsExactly(1L, 2L, 3L);
    }

    @Test
    void envelopesKeepIndependentSequences() {
        // given
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        ObservationCaptureEnvelope first = new ObservationCaptureEnvelope(agentInstanceId);
        ObservationCaptureEnvelope second = new ObservationCaptureEnvelope(agentInstanceId);

        // when/then
        assertThat(sealedSequence(first)).isEqualTo(1L);
        assertThat(sealedSequence(second)).isEqualTo(1L);
        assertThat(sealedSequence(first)).isEqualTo(2L);
    }

    @Test
    void sealWritesSharedEnvelopeFields() {
        // given
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        Map<String, Object> values = new LinkedHashMap<>();

        // when
        new ObservationCaptureEnvelope(agentInstanceId).open().sealInto(values);

        // then
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
        // given
        ObservationCaptureEnvelope.OpenCapture openCapture = new ObservationCaptureEnvelope(AgentInstanceId.random()).open();
        Thread.sleep(10L);

        Map<String, Object> values = new LinkedHashMap<>();
        // when
        openCapture.sealInto(values);

        // then
        assertThat((Long) values.get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_COMPLETED_AT))
                .isGreaterThan((Long) values.get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_STARTED_AT));
    }

    @Test
    void constructorRejectsMissingAgentInstanceId() {
        // given/when/then
        assertThatThrownBy(() -> new ObservationCaptureEnvelope(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("agentInstanceId");
    }

    @Test
    void sealRejectsMissingValues() {
        // given/when/then
        assertThatThrownBy(() -> new ObservationCaptureEnvelope(AgentInstanceId.random()).open().sealInto(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("values");
    }

    @Test
    void observationEnvelopeItemNamesAreSharedAcrossContracts() {
        // given/when/then
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
