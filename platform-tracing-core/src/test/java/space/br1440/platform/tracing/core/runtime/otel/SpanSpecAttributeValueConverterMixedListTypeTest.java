package space.br1440.platform.tracing.core.runtime.otel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanSpecAttributeValueConverterMixedListTypeTest {

    @Test
    void mixedTypeList_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> SpanSpecAttributeValueConverter.fromOtelValue(List.of("a", 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mixed-type list");
    }

    @Test
    void unsupportedElementType_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> SpanSpecAttributeValueConverter.fromOtelValue(List.of(new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported list attribute element type");
    }

    @Test
    void nullSingletonListElement_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> SpanSpecAttributeValueConverter.fromOtelValue(java.util.Collections.singletonList(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Null list elements are not supported");
    }

    @Test
    void nullListElementInMixedList_throwsIllegalArgumentException() {
        var list = new java.util.ArrayList<Object>();
        list.add("a");
        list.add(null);
        assertThatThrownBy(() -> SpanSpecAttributeValueConverter.fromOtelValue(list))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mixed-type list");
    }
}
