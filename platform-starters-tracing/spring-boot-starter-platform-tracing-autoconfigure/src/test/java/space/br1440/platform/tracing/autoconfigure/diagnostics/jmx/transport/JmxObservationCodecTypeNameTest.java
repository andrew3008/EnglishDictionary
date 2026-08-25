package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.SimpleType;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JmxObservationCodecTypeNameTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("normativeTypeNames")
    void matchingTypeNamePasses(String typeName) throws OpenDataException {
        CompositeData composite = compositeOf(typeName);

        JmxObservationCodec.requireCompositeTypeName(composite, typeName);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("normativeTypeNames")
    void mismatchedTypeNameIsContractViolation(String expectedTypeName) throws OpenDataException {
        CompositeData composite = compositeOf("space.br1440.platform.tracing.NotTheExpectedType");

        assertThatThrownBy(() -> JmxObservationCodec.requireCompositeTypeName(composite, expectedTypeName))
                .isInstanceOf(JmxContractViolationException.class)
                .satisfies(e -> assertThat(((JmxContractViolationException) e).kind())
                        .isEqualTo(JmxDiagnosticKind.COMPOSITE_TYPE_NAME_MISMATCH));
    }

    private static Stream<String> normativeTypeNames() {
        return Stream.of(
                JmxObservationWireContract.SAMPLING_COMPOSITE_TYPE_NAME,
                JmxObservationWireContract.SCRUBBING_COMPOSITE_TYPE_NAME,
                JmxObservationWireContract.VALIDATION_COMPOSITE_TYPE_NAME,
                JmxObservationWireContract.EXPORT_COMPOSITE_TYPE_NAME,
                JmxObservationWireContract.EXPORT_PROCESSOR_VALUES_TYPE_NAME,
                JmxObservationWireContract.EXPORT_EXPORTER_VALUES_TYPE_NAME,
                JmxObservationWireContract.PROCESSOR_METRICS_COMPOSITE_TYPE_NAME,
                JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_VALUES_TYPE_NAME,
                JmxObservationWireContract.PROCESSOR_METRICS_COMPOSITE_VALUES_TYPE_NAME,
                JmxObservationWireContract.PROCESSOR_METRICS_METRICS_VALUES_TYPE_NAME,
                JmxObservationWireContract.DIAGNOSTICS_COMPOSITE_TYPE_NAME);
    }

    private static CompositeData compositeOf(String typeName) throws OpenDataException {
        CompositeType type = new CompositeType(
                typeName, "Синтетический composite для проверки типового имени",
                new String[]{"marker"}, new String[]{"marker"}, new SimpleType<?>[]{SimpleType.STRING}
        );

        return new CompositeDataSupport(type, Map.of("marker", "value"));
    }
}
