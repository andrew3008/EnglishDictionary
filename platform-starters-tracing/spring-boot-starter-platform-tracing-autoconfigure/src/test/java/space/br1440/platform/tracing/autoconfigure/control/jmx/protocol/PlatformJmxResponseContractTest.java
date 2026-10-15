package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

final class PlatformJmxResponseContractTest {

    private static final String AGENT_INSTANCE_ID = "d2bc38ab-602d-4af1-925b-7a786f6e7e4c";
    private static final String ATTEMPT_ID = "attempt-7";

    @Test
    void completeSuccessfulResponsePassesStructuralAndCorrelationValidation() throws Exception {
        // given
        CompositeData response = response(Map.of());

        // when
        PlatformJmxResponse decoded = PlatformJmxResponse.decode(response);

        // then
        assertThat(decoded.acceptedSequence()).isEqualTo(8L);
        assertThat(decoded.attemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(decoded.messageCode()).isEqualTo(PlatformJmxResponse.MessageCode.NONE);
        assertThat(decoded.outcome()).isEqualTo(PlatformJmxResponse.Outcome.APPLIED);
        assertThat(decoded.agentInstanceId()).isEqualTo(AGENT_INSTANCE_ID);
        assertThat(decoded.violationCodes()).isEmpty();
        assertThatCode(() -> decoded.validateCorrelation(
                AGENT_INSTANCE_ID,
                7L,
                ATTEMPT_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void failedOutcomeWithoutExactFailureEvidenceIsRejected() throws Exception {
        // given
        CompositeData response = response(Map.of(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME,
                "FAILED"));

        // when
        // then
        assertThatThrownBy(() -> PlatformJmxResponse.decode(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact failure evidence");
    }

    @Test
    void unchangedOutcomeRequiresAcceptedSequenceToMatchTheRequestExactly() throws Exception {
        // given
        CompositeData response = response(Map.of(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME,
                "UNCHANGED",
                JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ACCEPTED_SEQUENCE,
                7L));

        // when
        PlatformJmxResponse decoded = PlatformJmxResponse.decode(response);

        // then
        assertThatCode(() -> decoded.validateCorrelation(
                AGENT_INSTANCE_ID,
                7L,
                ATTEMPT_ID))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> decoded.validateCorrelation(
                AGENT_INSTANCE_ID,
                6L,
                ATTEMPT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acceptedSequence");
    }

    @Test
    void rejectedOutcomeWithExactlyOneEvidenceCodeDecodesSuccessfully() throws Exception {
        // given
        CompositeData response = response(Map.of(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME,
                "REJECTED",
                JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE,
                "INVALID_VALUE",
                JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_VIOLATION_CODES,
                new String[]{"INVALID_VALUE"}));

        // when
        PlatformJmxResponse decoded = PlatformJmxResponse.decode(response);

        // then
        assertThat(decoded.outcome()).isEqualTo(PlatformJmxResponse.Outcome.REJECTED);
        assertThat(decoded.messageCode()).isEqualTo(PlatformJmxResponse.MessageCode.INVALID_VALUE);
        assertThat(decoded.violationCodes()).containsExactly(PlatformJmxResponse.MessageCode.INVALID_VALUE);
    }

    @Test
    void successfulOutcomeWithAnyFailureMessageCodeIsRejectedEvenWithoutViolationCodes() throws Exception {
        // given
        CompositeData response = response(Map.of(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE,
                "TYPE_MISMATCH"));

        // when
        // then
        assertThatThrownBy(() -> PlatformJmxResponse.decode(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("successful outcome contains failure evidence");
    }

    @Test
    void duplicateViolationCodesAreRejected() throws Exception {
        // given
        CompositeData response = response(Map.of(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME,
                "REJECTED",
                JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE,
                "INVALID_VALUE",
                JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_VIOLATION_CODES,
                new String[]{"INVALID_VALUE", "INVALID_VALUE"}));

        // when
        // then
        assertThatThrownBy(() -> PlatformJmxResponse.decode(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
    }

    @Test
    void successfulResponseWithMismatchedRequestCorrelationIsRejected() throws Exception {
        // given
        PlatformJmxResponse decoded = PlatformJmxResponse.decode(response(Map.of()));

        // when
        // then
        assertThatThrownBy(() -> decoded.validateCorrelation(
                AGENT_INSTANCE_ID,
                7L,
                "different-attempt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptId");

        assertThatThrownBy(() -> decoded.validateCorrelation(
                "different-runtime",
                7L,
                ATTEMPT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentInstanceId");

        assertThatThrownBy(() -> decoded.validateCorrelation(
                AGENT_INSTANCE_ID,
                8L,
                ATTEMPT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acceptedSequence");
    }

    @Test
    void constructorRejectsNegativeAcceptedSequenceDirectly() {
        // given
        // when
        // then
        assertThatThrownBy(() -> new PlatformJmxResponse(
                -1L,
                ATTEMPT_ID,
                PlatformJmxResponse.MessageCode.NONE,
                PlatformJmxResponse.Outcome.APPLIED,
                AGENT_INSTANCE_ID,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acceptedSequence must not be negative");
    }

    @Test
    void constructorRejectsDuplicateViolationCodesDirectly() {
        // given
        // when
        // then
        assertThatThrownBy(() -> new PlatformJmxResponse(
                8L,
                ATTEMPT_ID,
                PlatformJmxResponse.MessageCode.INVALID_VALUE,
                PlatformJmxResponse.Outcome.REJECTED,
                AGENT_INSTANCE_ID,
                List.of(PlatformJmxResponse.MessageCode.INVALID_VALUE, PlatformJmxResponse.MessageCode.INVALID_VALUE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
    }

    @Test
    void constructorRejectsInconsistentEvidenceForSuccessfulOutcomeDirectly() {
        // given
        // when
        // then
        assertThatThrownBy(() -> new PlatformJmxResponse(
                8L,
                ATTEMPT_ID,
                PlatformJmxResponse.MessageCode.INVALID_VALUE,
                PlatformJmxResponse.Outcome.APPLIED,
                AGENT_INSTANCE_ID,
                List.of(PlatformJmxResponse.MessageCode.INVALID_VALUE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("successful outcome contains failure evidence");
    }

    private static CompositeData response(Map<String, Object> overrides) throws OpenDataException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ACCEPTED_SEQUENCE, 8L);
        fields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ATTEMPT_ID, ATTEMPT_ID);
        fields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE, "NONE");
        fields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME, "APPLIED");
        fields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_AGENT_INSTANCE_ID, AGENT_INSTANCE_ID);
        fields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_VIOLATION_CODES, new String[0]);
        fields.putAll(overrides);

        return new CompositeDataSupport(JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE, fields);
    }
}
