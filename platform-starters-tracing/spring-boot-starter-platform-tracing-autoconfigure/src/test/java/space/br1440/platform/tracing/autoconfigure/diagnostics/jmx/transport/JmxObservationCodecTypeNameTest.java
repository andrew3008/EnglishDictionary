package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Stream;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.SimpleType;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

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
                JmxWireContract.OPEN_TYPE_SAMPLING_OBSERVATION_TYPE_NAME,
                JmxWireContract.OPEN_TYPE_SCRUBBING_OBSERVATION_TYPE_NAME,
                JmxWireContract.OPEN_TYPE_VALIDATION_OBSERVATION_TYPE_NAME,
                JmxWireContract.OPEN_TYPE_EXPORT_OBSERVATION_TYPE_NAME,
                JmxWireContract.OPEN_TYPE_EXPORT_PROCESSOR_VALUES_TYPE_NAME,
                JmxWireContract.OPEN_TYPE_SAFE_EXPORTER_VALUES_TYPE_NAME,
                JmxWireContract.OPEN_TYPE_PROCESSOR_METRICS_OBSERVATION_TYPE_NAME,
                JmxWireContract.OPEN_TYPE_PROCESSOR_SPAN_TIMEOUT_VALUES_TYPE_NAME,
                JmxWireContract.OPEN_TYPE_PROCESSOR_COMPOSITE_VALUES_TYPE_NAME,
                JmxWireContract.OPEN_TYPE_PROCESSOR_METRICS_VALUES_TYPE_NAME,
                JmxWireContract.OPEN_TYPE_DIAGNOSTICS_OBSERVATION_TYPE_NAME);
    }

    private static CompositeData compositeOf(String typeName) throws OpenDataException {
        CompositeType type = new CompositeType(
                typeName, "Синтетический composite для проверки типового имени",
                new String[]{"marker"}, new String[]{"marker"}, new SimpleType<?>[]{SimpleType.STRING}
        );

        return new CompositeDataSupport(type, Map.of("marker", "value"));
    }
}
