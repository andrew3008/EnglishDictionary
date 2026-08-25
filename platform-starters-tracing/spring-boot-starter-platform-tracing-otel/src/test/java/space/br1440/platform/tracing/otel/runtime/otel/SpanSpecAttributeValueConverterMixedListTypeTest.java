package space.br1440.platform.tracing.otel.runtime.otel;

import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanSpecAttributeValueConverterMixedListTypeTest {

    private static final AttributeKey<List<String>> STRING_LIST_KEY = AttributeKey.stringArrayKey("test");

    @Test
    void mixedTypeList_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> SpanSpecAttributeValueConverter.fromOtelValue(STRING_LIST_KEY, List.of("a", 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mixed-type list");
    }

    @Test
    void unsupportedElementType_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> SpanSpecAttributeValueConverter.fromOtelValue(
                STRING_LIST_KEY, List.of(new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported list attribute element type");
    }

    @Test
    void nullSingletonListElement_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> SpanSpecAttributeValueConverter.fromOtelValue(
                STRING_LIST_KEY, java.util.Collections.singletonList(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Null list elements are not supported");
    }

    @Test
    void nullListElementInMixedList_throwsIllegalArgumentException() {
        var list = new java.util.ArrayList<Object>();
        list.add("a");
        list.add(null);
        assertThatThrownBy(() -> SpanSpecAttributeValueConverter.fromOtelValue(STRING_LIST_KEY, list))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mixed-type list");
    }
}
