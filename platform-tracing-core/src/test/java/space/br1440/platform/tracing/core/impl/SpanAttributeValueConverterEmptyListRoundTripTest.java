package space.br1440.platform.tracing.core.impl;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.spec.SpanAttributeValue;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpanAttributeValueConverterEmptyListRoundTripTest {

    @Test
    void emptyList_mapsToEmptyStringList() {
        SpanAttributeValue result = SpanAttributeValueConverter.fromOtelValue(List.of());
        assertThat(result).isInstanceOf(SpanAttributeValue.StringListValue.class);
        assertThat(((SpanAttributeValue.StringListValue) result).values()).isEmpty();
    }

    @Test
    void homogeneousStringList_roundTrips() {
        SpanAttributeValue result = SpanAttributeValueConverter.fromOtelValue(List.of("a", "b"));
        assertThat(result).isInstanceOf(SpanAttributeValue.StringListValue.class);
        assertThat(((SpanAttributeValue.StringListValue) result).values()).containsExactly("a", "b");
    }

    @Test
    void homogeneousLongList_roundTrips() {
        SpanAttributeValue result = SpanAttributeValueConverter.fromOtelValue(List.of(1L, 2L));
        assertThat(result).isInstanceOf(SpanAttributeValue.LongListValue.class);
        assertThat(((SpanAttributeValue.LongListValue) result).values()).containsExactly(1L, 2L);
    }

    @Test
    void homogeneousDoubleList_roundTrips() {
        SpanAttributeValue result = SpanAttributeValueConverter.fromOtelValue(List.of(1.0, 2.0));
        assertThat(result).isInstanceOf(SpanAttributeValue.DoubleListValue.class);
        assertThat(((SpanAttributeValue.DoubleListValue) result).values()).containsExactly(1.0, 2.0);
    }

    @Test
    void homogeneousBooleanList_roundTrips() {
        SpanAttributeValue result = SpanAttributeValueConverter.fromOtelValue(List.of(true, false));
        assertThat(result).isInstanceOf(SpanAttributeValue.BooleanListValue.class);
        assertThat(((SpanAttributeValue.BooleanListValue) result).values()).containsExactly(true, false);
    }
}
