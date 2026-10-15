package space.br1440.platform.tracing.jmx.contract.tool.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;

class PolicyDigestSectionValidatorTest {

    @Test
    void rejectsAlgorithmOtherThanSha256() {
        // given
        Map<String, Object> digest = new HashMap<>(validPolicyDigest());
        digest.put("algorithmId", "SHA-1");

        // when / then
        assertThatThrownBy(() -> PolicyDigestSectionValidator.validate(context(digest)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_VALUE_UNSUPPORTED"));
    }

    private static ContractValidationContext context(Map<String, Object> digest) {
        return new ContractValidationContext(Map.of("policyDigest", digest), Map.of(), Map.of(), Map.of());
    }

    private static Map<String, Object> validPolicyDigest() {
        return Map.of(
                "algorithmId", "SHA-256",
                "algorithmVersion", 1,
                "encodingVersion", 1,
                "domainSeparator", "platform-tracing-policy-v1",
                "routeOrdering", "unsigned-utf8-lexicographic",
                "fields", List.of(
                        Map.of("id", 1, "name", "samplingEnabled", "type", "boolean"),
                        Map.of("id", 2, "name", "samplingRatio", "type", "double"),
                        Map.of("id", 3, "name", "samplingRouteRatios", "type", "sortedUtf8DoubleMap"),
                        Map.of("id", 4, "name", "validationLevel", "type", "asciiToken"),
                        Map.of("id", 5, "name", "exporterEnabled", "type", "boolean"),
                        Map.of("id", 6, "name", "traceControlHeadersEnabled", "type", "boolean"),
                        Map.of("id", 7, "name", "diagnosticsLogLevel", "type", "asciiToken")
                )
        );
    }
}
