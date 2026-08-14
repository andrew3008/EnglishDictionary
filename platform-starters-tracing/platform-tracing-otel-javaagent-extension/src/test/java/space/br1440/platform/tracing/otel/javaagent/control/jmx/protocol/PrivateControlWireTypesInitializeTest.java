package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PrivateControlWireTypes.WireField;

/**
 * Startup/invariant тест для Agent-side {@code PrivateControlWireTypes.initialize()}
 * (Commit 3, §8 из плана {@code wire-field-descriptors}).
 * <p>
 * {@code PrivateControlWriterMBean} вызывает {@code initialize()} до регистрации private
 * writer MBean; этот тест независимо доказывает, что вызов завершается успешно и что exact
 * membership validation действительно опирается на построенные схемы, а не на пустой
 * compatibility-заглушку.
 */
class PrivateControlWireTypesInitializeTest {

    @Test
    void initializeSucceedsBeforeAnyMBeanRegistration() {
        assertThatCode(PrivateControlWireTypes::initialize).doesNotThrowAnyException();
    }

    @Test
    void everyWireNameIsUnique() {
        List<String> wireNames = Arrays.stream(WireField.values())
                .map(WireField::wireName)
                .toList();

        assertThat(wireNames).doesNotHaveDuplicates();
        assertThat(EnumSet.allOf(WireField.class)).hasSize(25);
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
                WireField.RUNTIME_INSTANCE_ID,
                WireField.VIOLATION_CODES);
    }

    @Test
    void acceptedCompleteKeySetMatchesMembership() {
        assertKeySet(PrivateControlWireTypes.ACCEPTED_COMPLETE.keySet(),
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
