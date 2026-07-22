package space.br1440.platform.tracing.core.control.protocol;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimePolicyControlDomainValidatorTest {

    @Test
    void acceptsDecodedRuntimePolicyInsideDomainBounds() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);
        payload.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, Map.of("/internal", 0.25d));

        TracingControlDomainValidationResult result = RuntimePolicyControlDomainValidator.validate(payload);

        assertThat(result.valid()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void rejectsDecodedRuntimePolicyOutsideDomainBounds() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 1.5d);

        TracingControlDomainValidationResult result = RuntimePolicyControlDomainValidator.validate(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).isNotEmpty();
    }

    @Test
    void emptyApplyMutationIsRejectedByDomainValidator() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolOperation.APPLY_RUNTIME_POLICY.wireValue());
        payload.put(TracingControlProtocolKeys.DIAGNOSTICS_REQUEST_ID, "empty-apply");

        TracingControlDomainValidationResult result = RuntimePolicyControlDomainValidator.validate(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).contains("empty mutation rejected");
    }
}
