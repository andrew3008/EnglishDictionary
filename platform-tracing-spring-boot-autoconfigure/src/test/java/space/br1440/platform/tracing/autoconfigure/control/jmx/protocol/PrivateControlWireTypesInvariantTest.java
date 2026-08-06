package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PrivateControlWireTypes.WireField;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Гарантирует внутреннюю согласованность каталога {@link WireField} независимо от
 * {@link PrivateControlWireSchemaGoldenTest}: уникальность wire-имён и точное соответствие
 * key-set каждой из пяти схем заявленному в дескрипторах membership.
 * <p>
 * Сверка Spring-схемы с Agent-схемой здесь не дублируется — она уже выполняется
 * {@code ObjectNameConstantsConsistencyTest} и {@code PrivateControlWireSchemaGoldenTest}
 * (метод {@code springAndAgentOpenTypesAreMutuallyEqual}), оба обязаны оставаться зелёными.
 */
class PrivateControlWireTypesInvariantTest {

    @Test
    void everyWireNameIsUnique() {
        List<String> wireNames = Arrays.stream(WireField.values())
                .map(WireField::wireName)
                .toList();

        assertThat(wireNames).doesNotHaveDuplicates();
        assertThat(EnumSet.allOf(WireField.class)).hasSize(27);
    }

    @Test
    void routeRowKeySetMatchesMembership() {
        assertKeySet(PrivateControlWireTypes.ROUTE_ROW.keySet(),
                WireField.ROUTE_RATIO, WireField.ROUTE_PREFIX);
    }

    @Test
    void policyKeySetMatchesMembership() {
        assertKeySet(PrivateControlWireTypes.POLICY.keySet(),
                WireField.DIAGNOSTICS_LOG_LEVEL,
                WireField.EXPORTER_ENABLED,
                WireField.SAMPLING_ENABLED,
                WireField.SAMPLING_RATIO,
                WireField.SAMPLING_ROUTE_RATIOS,
                WireField.TRACE_CONTROL_HEADERS_ENABLED,
                WireField.VALIDATION_ENABLED,
                WireField.VALIDATION_STRICT);
    }

    @Test
    void requestKeySetMatchesMembership() {
        assertKeySet(PrivateControlWireTypes.REQUEST.keySet(),
                WireField.APPLY_TRIGGER,
                WireField.ATTEMPT_ID,
                WireField.CANDIDATE_DIGEST,
                WireField.EXPECTED_ACCEPTED_SEQUENCE,
                WireField.EXPECTED_RUNTIME_INSTANCE_ID,
                WireField.PROTOCOL_VERSION,
                WireField.RUNTIME_POLICY,
                WireField.SOURCE_AUTHORITY,
                WireField.SOURCE_REVISION);
    }

    @Test
    void responseKeySetMatchesMembership() {
        assertKeySet(PrivateControlWireTypes.RESPONSE.keySet(),
                WireField.ACCEPTED_SEQUENCE,
                WireField.ATTEMPT_ID,
                WireField.MESSAGE_CODE,
                WireField.OUTCOME,
                WireField.PAYLOAD_DIGEST,
                WireField.PROTOCOL_VERSION,
                WireField.RESTART_REQUIRED,
                WireField.RESTART_REQUIRED_KEYS,
                WireField.RUNTIME_INSTANCE_ID,
                WireField.VIOLATION_CODES);
    }

    @Test
    void completeResponseKeySetMatchesMembership() {
        assertKeySet(PrivateControlWireTypes.COMPLETE_RESPONSE.keySet(),
                WireField.ACCEPTED_SEQUENCE,
                WireField.RUNTIME_INSTANCE_ID,
                WireField.SAMPLING_ENABLED,
                WireField.SAMPLING_RATIO,
                WireField.SAMPLING_ROUTE_RATIOS,
                WireField.VALIDATION_ENABLED,
                WireField.VALIDATION_STRICT,
                WireField.EXPORTER_ENABLED,
                WireField.TRACE_CONTROL_HEADERS_ENABLED,
                WireField.DIAGNOSTICS_LOG_LEVEL);
    }

    @Test
    void routesTableIsIndexedByRoutePrefix() {
        assertThat(PrivateControlWireTypes.ROUTES.getIndexNames())
                .containsExactly(WireField.ROUTE_PREFIX.wireName());
    }

    private static void assertKeySet(Set<String> actual, WireField... expected) {
        Set<String> expectedNames = Arrays.stream(expected)
                .map(WireField::wireName)
                .collect(Collectors.toSet());
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expectedNames);
    }
}
