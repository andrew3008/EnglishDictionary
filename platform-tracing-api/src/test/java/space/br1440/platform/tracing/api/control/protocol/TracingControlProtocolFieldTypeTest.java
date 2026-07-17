package space.br1440.platform.tracing.api.control.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the narrow {@link TracingControlProtocolFieldType#ratioBounded()} metadata flag.
 *
 * <p><strong>v1-local compromise notice:</strong> {@code ratioBounded()} returns
 * {@code true} for {@link TracingControlProtocolFieldType#DOUBLE} because in v1 the only
 * {@code DOUBLE} field in the schema ({@code sampling.ratio}) carries a [0.0, 1.0] bound.
 * This is an intentional v1-local compromise, not a general truth about the {@code DOUBLE}
 * wire type.
 *
 * <p><strong>If a future non-ratio {@code DOUBLE} field appears</strong>, this flag must
 * migrate from {@code TracingControlProtocolFieldType} to a per-field constraint descriptor.
 * At that point {@code DOUBLE.ratioBounded()} should return {@code false}.
 */
@DisplayName("TracingControlProtocolFieldType.ratioBounded(): v1-local enum metadata flag")
class TracingControlProtocolFieldTypeTest {

    @Test
    @DisplayName("DOUBLE.ratioBounded() == true")
    void doubleIsRatioBounded() {
        assertThat(TracingControlProtocolFieldType.DOUBLE.ratioBounded()).isTrue();
    }

    @Test
    @DisplayName("All types except DOUBLE have ratioBounded() == false")
    void allOtherTypesAreNotRatioBounded() {
        List<TracingControlProtocolFieldType> nonDoubleBounded = Arrays.stream(TracingControlProtocolFieldType.values())
                .filter(t -> t != TracingControlProtocolFieldType.DOUBLE)
                .filter(TracingControlProtocolFieldType::ratioBounded)
                .collect(Collectors.toList());

        assertThat(nonDoubleBounded)
                .as("No type other than DOUBLE should have ratioBounded() == true in v1")
                .isEmpty();
    }

    @Test
    @DisplayName("Verify each non-DOUBLE type individually for clarity")
    void eachNonDoubleTypeIsNotRatioBounded() {
        assertThat(TracingControlProtocolFieldType.STRING.ratioBounded()).isFalse();
        assertThat(TracingControlProtocolFieldType.BOOLEAN.ratioBounded()).isFalse();
        assertThat(TracingControlProtocolFieldType.INTEGER.ratioBounded()).isFalse();
        assertThat(TracingControlProtocolFieldType.LONG.ratioBounded()).isFalse();
        assertThat(TracingControlProtocolFieldType.STRING_ARRAY.ratioBounded()).isFalse();
        assertThat(TracingControlProtocolFieldType.ROUTE_RATIOS_MAP.ratioBounded()).isFalse();
    }
}
