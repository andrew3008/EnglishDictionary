package space.br1440.platform.tracing.core.runtime.otel;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpanSpecAttributeValueConverterEmptyListRoundTripTest {

    /**
     * OTel Attributes do not preserve element type for empty lists, so empty list maps to
     * {@code StringListValue} by convention.
     */
    @Test
    void emptyList_mapsToEmptyStringList() {
        SpanSpecAttributeValue result = SpanSpecAttributeValueConverter.fromOtelValue(List.of());
        assertThat(result).isInstanceOf(SpanSpecAttributeValue.StringListValue.class);
        assertThat(((SpanSpecAttributeValue.StringListValue) result).values()).isEmpty();
    }

    @Test
    void homogeneousStringList_roundTrips() {
        SpanSpecAttributeValue result = SpanSpecAttributeValueConverter.fromOtelValue(List.of("a", "b"));
        assertThat(result).isInstanceOf(SpanSpecAttributeValue.StringListValue.class);
        assertThat(((SpanSpecAttributeValue.StringListValue) result).values()).containsExactly("a", "b");
    }

    @Test
    void homogeneousLongList_roundTrips() {
        SpanSpecAttributeValue result = SpanSpecAttributeValueConverter.fromOtelValue(List.of(1L, 2L));
        assertThat(result).isInstanceOf(SpanSpecAttributeValue.LongListValue.class);
        assertThat(((SpanSpecAttributeValue.LongListValue) result).values()).containsExactly(1L, 2L);
    }

    @Test
    void homogeneousDoubleList_roundTrips() {
        SpanSpecAttributeValue result = SpanSpecAttributeValueConverter.fromOtelValue(List.of(1.0, 2.0));
        assertThat(result).isInstanceOf(SpanSpecAttributeValue.DoubleListValue.class);
        assertThat(((SpanSpecAttributeValue.DoubleListValue) result).values()).containsExactly(1.0, 2.0);
    }

    @Test
    void homogeneousBooleanList_roundTrips() {
        SpanSpecAttributeValue result = SpanSpecAttributeValueConverter.fromOtelValue(List.of(true, false));
        assertThat(result).isInstanceOf(SpanSpecAttributeValue.BooleanListValue.class);
        assertThat(((SpanSpecAttributeValue.BooleanListValue) result).values()).containsExactly(true, false);
    }
}
