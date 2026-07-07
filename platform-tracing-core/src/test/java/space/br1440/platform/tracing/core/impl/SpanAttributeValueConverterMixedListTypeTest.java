package space.br1440.platform.tracing.core.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanAttributeValueConverterMixedListTypeTest {

    @Test
    void mixedTypeList_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> SpanAttributeValueConverter.fromOtelValue(List.of("a", 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mixed-type list");
    }

    @Test
    void unsupportedElementType_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> SpanAttributeValueConverter.fromOtelValue(List.of(new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported list attribute element type");
    }

    @Test
    void nullListElement_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> SpanAttributeValueConverter.fromOtelValue(java.util.Collections.singletonList(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Null list elements are not supported");
    }
}
