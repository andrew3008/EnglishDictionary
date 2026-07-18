package space.br1440.platform.tracing.api.control.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migrated from {@code schema/RequiredKeysEquivalenceTest}.
 *
 * <p>The original version relied on
 * the former public schema-introspection method,
 * which was deleted in slice-3. This rewrite verifies the same invariant
 * behaviourally via {@link TracingControlProtocol#decode(Map)}:
 *
 * <ul>
 *   <li>A payload containing <strong>both</strong> {@code contractVersion}
 *       and {@code operation} must not produce {@code MISSING_REQUIRED_KEY}
 *       violations for those envelope fields.</li>
 *   <li>A payload missing {@code contractVersion} must produce
 *       {@code MISSING_REQUIRED_KEY} for that key.</li>
 *   <li>A payload missing the {@code operation} key must produce
 *       {@code MISSING_REQUIRED_KEY} for that key.</li>
 * </ul>
 *
 * <p>The invariant holds for every value of
 * {@link TracingControlProtocolOperation}, proving that
 * {@code contractVersion} and {@code operation} are universally required
 * envelope fields — exactly what the original test expressed.
 */
@DisplayName("Required envelope keys: contractVersion and operation are always required")
class RequiredKeysEquivalenceTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Decode a payload that contains only the supplied keys (null = absent). */
    private static TracingControlProtocolDecodeResult decodeEnvelope(
            Object contractVersion, Object operation) {
        Map<String, Object> payload = new HashMap<>();
        if (contractVersion != null) {
            payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, contractVersion);
        }
        if (operation != null) {
            payload.put(TracingControlProtocolKeys.OPERATION, operation);
        }
        return TracingControlProtocol.current().decode(payload);
    }

    // -----------------------------------------------------------------------
    // Both envelope keys present -> no envelope MISSING_REQUIRED_KEY
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "{0}: valid envelope -> no envelope MISSING_REQUIRED_KEY")
    @EnumSource(TracingControlProtocolOperation.class)
    @DisplayName("payload with contractVersion=1 and matching operation name has no envelope missing-key violation")
    void validEnvelopeHasNoMissingKeyViolation(TracingControlProtocolOperation operation) {
        TracingControlProtocolDecodeResult result =
                decodeEnvelope(1, operation.name());

        assertThat(result.violations())
                .as("no MISSING_REQUIRED_KEY for envelope fields when op=%s", operation)
                .noneMatch(v ->
                        v.code() == TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY
                        && (v.key().equals(TracingControlProtocolKeys.CONTRACT_VERSION)
                            || v.key().equals(TracingControlProtocolKeys.OPERATION)));
    }

    // -----------------------------------------------------------------------
    // contractVersion absent -> MISSING_REQUIRED_KEY
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "{0}: contractVersion absent -> MISSING_REQUIRED_KEY")
    @EnumSource(TracingControlProtocolOperation.class)
    @DisplayName("payload without contractVersion always yields MISSING_REQUIRED_KEY for that key")
    void missingContractVersionYieldsMissingRequiredKey(TracingControlProtocolOperation operation) {
        Map<String, Object> payload = Map.of(
                TracingControlProtocolKeys.OPERATION, operation.name());
        TracingControlProtocolDecodeResult result =
                TracingControlProtocol.current().decode(payload);

        assertThat(result.valid())
                .as("decode must be invalid when contractVersion is absent (op=%s)", operation)
                .isFalse();
        assertThat(result.violations())
                .as("must contain MISSING_REQUIRED_KEY for contractVersion (op=%s)", operation)
                .anyMatch(v ->
                        v.code() == TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY
                        && v.key().equals(TracingControlProtocolKeys.CONTRACT_VERSION));
    }

    // -----------------------------------------------------------------------
    // operation key absent -> MISSING_REQUIRED_KEY
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "{0}: operation key absent in payload -> MISSING_REQUIRED_KEY")
    @EnumSource(TracingControlProtocolOperation.class)
    @DisplayName("payload without operation key always yields MISSING_REQUIRED_KEY for that key")
    void missingOperationKeyYieldsMissingRequiredKey(TracingControlProtocolOperation operation) {
        Map<String, Object> payload = Map.of(
                TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        TracingControlProtocolDecodeResult result =
                TracingControlProtocol.current().decode(payload);

        assertThat(result.valid())
                .as("decode must be invalid when operation key is absent (op=%s)", operation)
                .isFalse();
        assertThat(result.violations())
                .as("must contain MISSING_REQUIRED_KEY for operation key (op=%s)", operation)
                .anyMatch(v ->
                        v.code() == TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY
                        && v.key().equals(TracingControlProtocolKeys.OPERATION));
    }
}
