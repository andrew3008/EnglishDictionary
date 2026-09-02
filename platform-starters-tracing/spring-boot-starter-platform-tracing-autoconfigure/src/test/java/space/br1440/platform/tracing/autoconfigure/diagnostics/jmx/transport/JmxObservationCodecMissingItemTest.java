package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.SimpleType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JmxObservationCodecMissingItemTest {

    @Test
    void unknownItemNameIsContractViolationInsteadOfRawJmxFailure() throws OpenDataException {
        // given
        CompositeData composite = compositeWithSingleStringItem();

        // when / then
        assertThatThrownBy(() -> JmxObservationCodec.getLong(composite, "absentItem"))
                .isInstanceOf(JmxContractViolationException.class)
                .satisfies(failure -> assertThat(((JmxContractViolationException) failure).kind())
                        .isEqualTo(JmxDiagnosticKind.MISSING_OBSERVATION_ITEM));
    }

    @Test
    void declaredItemWithWrongTypeRemainsTypeMismatch() throws OpenDataException {
        // given
        CompositeData composite = compositeWithSingleStringItem();

        // when / then
        assertThatThrownBy(() -> JmxObservationCodec.getLong(composite, "marker"))
                .isInstanceOf(JmxContractViolationException.class)
                .satisfies(failure -> assertThat(((JmxContractViolationException) failure).kind())
                        .isEqualTo(JmxDiagnosticKind.VALUE_TYPE_MISMATCH));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void nonPositiveEnvelopeValueIsSemanticsViolationRatherThanRawRecordFailure(long value) throws OpenDataException {
        // given
        CompositeData composite = compositeWithLongItem(value);

        // when / then
        assertThatThrownBy(() -> JmxObservationCodec.getPositiveLong(composite, "captureSequence"))
                .isInstanceOf(JmxContractViolationException.class)
                .satisfies(failure -> assertThat(((JmxContractViolationException) failure).kind())
                        .isEqualTo(JmxDiagnosticKind.VALUE_SEMANTICS_VIOLATION));
    }

    @Test
    void positiveEnvelopeValuePassesThrough() throws OpenDataException {
        // given
        CompositeData composite = compositeWithLongItem(1L);

        // when / then
        assertThat(JmxObservationCodec.getPositiveLong(composite, "captureSequence")).isEqualTo(1L);
    }

    private static CompositeData compositeWithSingleStringItem() throws OpenDataException {
        CompositeType type = new CompositeType(
                "space.br1440.platform.tracing.MissingItemProbe",
                "Синтетический composite без запрашиваемого item",
                new String[]{"marker"}, new String[]{"marker"}, new SimpleType<?>[]{SimpleType.STRING}
        );

        return new CompositeDataSupport(type, Map.of("marker", "value"));
    }

    private static CompositeData compositeWithLongItem(long value) throws OpenDataException {
        CompositeType type = new CompositeType(
                "space.br1440.platform.tracing.EnvelopeProbe",
                "Синтетический composite со значением конверта",
                new String[]{"captureSequence"}, new String[]{"captureSequence"},
                new SimpleType<?>[]{SimpleType.LONG}
        );

        return new CompositeDataSupport(type, Map.of("captureSequence", value));
    }
}
