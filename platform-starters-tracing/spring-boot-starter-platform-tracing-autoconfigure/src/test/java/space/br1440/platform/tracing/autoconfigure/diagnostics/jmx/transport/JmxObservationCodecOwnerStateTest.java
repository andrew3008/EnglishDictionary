package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

/**
 * Discriminator-first decode для {@link JmxObservationCodec#ownerState}: payload владельца
 * не должен декодироваться, пока не подтверждено {@code ownerPresent = true}. До этого
 * рефакторинга payload строился заранее (в {@code *JmxView.decode}), поэтому повреждённый
 * placeholder absent-владельца мог породить ложный {@link JmxContractViolationException}.
 */
class JmxObservationCodecOwnerStateTest {

    private static final String PRESENT_ITEM = "ownerPresent";
    private static final String GENERATION_ITEM = "ownerGeneration";
    private static final String PAYLOAD_ITEM = "payload";

    @Test
    void absentOwnerWithDamagedPlaceholderDecodesToAbsentWithoutInvokingSupplier() throws OpenDataException {
        // Нормативный placeholder absent-владельца — это тип, который декодер ожидает
        // (например, TabularData для карты), но здесь намеренно подставлен несовместимый тип
        // (String вместо ожидаемой карты), чтобы доказать: Supplier для payload не вызывается.
        CompositeData composite = compositeOf(false, 0L, "damaged-placeholder-not-a-map");
        AtomicBoolean payloadSupplierInvoked = new AtomicBoolean(false);

        JmxOwnerState<Map<String, Long>> state = JmxObservationCodec.ownerState(
                composite, PRESENT_ITEM, GENERATION_ITEM, () -> {
                    payloadSupplierInvoked.set(true);
                    return JmxObservationCodec.getStringLongMap(
                            composite,
                            PAYLOAD_ITEM,
                            JmxWireContract.FIELD_SAMPLER_DECISION_COUNTS_KEY,
                            JmxWireContract.FIELD_SAMPLER_DECISION_COUNTS_VALUE);
                });

        assertThat(state).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(state.generation()).isZero();
        assertThat(payloadSupplierInvoked).isFalse();
    }

    @Test
    void nestedOwnerWithDamagedPlaceholderDecodesToAbsentWithoutInvokingSupplier() throws OpenDataException {
        // Тот же сценарий для nested-владельца (например, Export.processor / ProcessorMetrics.*):
        // presence-item'ы читаются из sub-composite тем же кодом, поведение идентично.
        CompositeData nestedComposite = compositeOf(false, 3L, 12345);
        AtomicBoolean payloadSupplierInvoked = new AtomicBoolean(false);

        JmxOwnerState<Long> state = JmxObservationCodec.ownerState(
                nestedComposite, PRESENT_ITEM, GENERATION_ITEM, () -> {
                    payloadSupplierInvoked.set(true);
                    return (long) JmxObservationCodec.getString(nestedComposite, PAYLOAD_ITEM).length();
                });

        assertThat(state).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(state.generation()).isEqualTo(3L);
        assertThat(payloadSupplierInvoked).isFalse();
    }

    @Test
    void presentOwnerWithValidGenerationDecodesPayload() throws OpenDataException {
        CompositeData composite = compositeOf(true, 7L, "ok");

        JmxOwnerState<String> state = JmxObservationCodec.ownerState(
                composite, PRESENT_ITEM, GENERATION_ITEM,
                () -> JmxObservationCodec.getString(composite, PAYLOAD_ITEM));

        assertThat(state).isInstanceOf(JmxOwnerState.Present.class);
        assertThat(state.generation()).isEqualTo(7L);
        assertThat(((JmxOwnerState.Present<String>) state).values()).isEqualTo("ok");
    }

    @Test
    void presentOwnerWithNonPositiveGenerationIsContractViolation() throws OpenDataException {
        CompositeData composite = compositeOf(true, 0L, "ok");

        assertThatThrownBy(() -> JmxObservationCodec.ownerState(
                composite, PRESENT_ITEM, GENERATION_ITEM,
                () -> JmxObservationCodec.getString(composite, PAYLOAD_ITEM)))
                .isInstanceOf(JmxContractViolationException.class)
                .satisfies(e -> assertThat(((JmxContractViolationException) e).kind())
                        .isEqualTo(JmxDiagnosticKind.OWNER_GENERATION_INVALID));
    }

    private static CompositeData compositeOf(boolean present, long generation, Object payload) throws OpenDataException {
        Map<String, OpenType<?>> itemTypes = new LinkedHashMap<>();
        itemTypes.put(PRESENT_ITEM, SimpleType.BOOLEAN);
        itemTypes.put(GENERATION_ITEM, SimpleType.LONG);
        itemTypes.put(PAYLOAD_ITEM, payload instanceof String ? SimpleType.STRING : SimpleType.INTEGER);

        CompositeType type = new CompositeType(
                "TestOwnerComposite", "Синтетический composite для discriminator-first теста",
                itemTypes.keySet().toArray(new String[0]),
                itemTypes.keySet().toArray(new String[0]),
                itemTypes.values().toArray(new OpenType<?>[0]));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put(PRESENT_ITEM, present);
        values.put(GENERATION_ITEM, generation);
        values.put(PAYLOAD_ITEM, payload);

        return new CompositeDataSupport(type, values);
    }
}
