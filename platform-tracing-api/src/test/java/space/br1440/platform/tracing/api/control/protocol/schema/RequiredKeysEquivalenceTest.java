package space.br1440.platform.tracing.api.control.protocol.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Required keys for operations")
class RequiredKeysEquivalenceTest {

    private static final Set<String> EXPECTED_REQUIRED = Set.of(
            TracingControlProtocolKeys.CONTRACT_VERSION,
            TracingControlProtocolKeys.OPERATION);

    @ParameterizedTest
    @EnumSource(TracingControlProtocolOperation.class)
    @DisplayName("requiredKeysFor(operation) returns contractVersion and operation")
    void requiredKeysForAllOperations(TracingControlProtocolOperation operation) {
        Set<String> required = new TreeSet<>(TracingControlProtocol.current().schema().requiredKeysFor(operation));

        assertThat(required).containsExactlyInAnyOrderElementsOf(new TreeSet<>(EXPECTED_REQUIRED));
    }
}
