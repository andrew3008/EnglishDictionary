package space.br1440.platform.tracing.api.control.protocol.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolTypes;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the narrow {@link TracingControlProtocolTypes#ratioBounded()} metadata flag
 * introduced in Phase 1.
 *
 * <p><strong>v1-local compromise notice:</strong> {@code ratioBounded()} returns
 * {@code true} for {@link TracingControlProtocolTypes#DOUBLE} because in v1 the only
 * {@code DOUBLE} field in the schema ({@code sampling.ratio}) carries a
 * {@code [0.0, 1.0]} bound. This is an intentional v1-local compromise, not a general
 * truth about the {@code DOUBLE} wire type.
 *
 * <p><strong>If a future non-ratio {@code DOUBLE} field appears</strong>, this flag
 * must migrate from {@code TracingControlProtocolTypes} to
 * {@code TracingControlProtocolFieldDescriptor} or an equivalent per-field constraint.
 * At that point, {@code DOUBLE.ratioBounded()} should return {@code false}, and the
 * bound should be expressed as descriptor metadata.
 */
@DisplayName("TracingControlProtocolTypes.ratioBounded(): v1-local enum metadata flag")
class TracingControlProtocolTypesTest {

    @Test
    @DisplayName("DOUBLE.ratioBounded() == true")
    void doubleIsRatioBounded() {
        assertThat(TracingControlProtocolTypes.DOUBLE.ratioBounded()).isTrue();
    }

    @Test
    @DisplayName("All types except DOUBLE have ratioBounded() == false")
    void allOtherTypesAreNotRatioBounded() {
        // v1-local compromise: ratioBounded() is true only for DOUBLE.
        // If a future non-ratio DOUBLE field appears, this flag must move to
        // field descriptor metadata — see class Javadoc.
        List<TracingControlProtocolTypes> nonDoubleBounded = Arrays.stream(TracingControlProtocolTypes.values())
                .filter(t -> t != TracingControlProtocolTypes.DOUBLE)
                .filter(TracingControlProtocolTypes::ratioBounded)
                .collect(Collectors.toList());

        assertThat(nonDoubleBounded)
                .as("No type other than DOUBLE should have ratioBounded() == true in v1")
                .isEmpty();
    }

    @Test
    @DisplayName("Verify each non-DOUBLE type individually for clarity")
    void eachNonDoubleTypeIsNotRatioBounded() {
        assertThat(TracingControlProtocolTypes.STRING.ratioBounded()).isFalse();
        assertThat(TracingControlProtocolTypes.BOOLEAN.ratioBounded()).isFalse();
        assertThat(TracingControlProtocolTypes.INTEGER.ratioBounded()).isFalse();
        assertThat(TracingControlProtocolTypes.LONG.ratioBounded()).isFalse();
        assertThat(TracingControlProtocolTypes.STRING_ARRAY.ratioBounded()).isFalse();
        assertThat(TracingControlProtocolTypes.ROUTE_RATIOS_MAP.ratioBounded()).isFalse();
    }
}
