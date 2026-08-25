package space.br1440.platform.tracing.api.span.spec;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanSpecBuilderListAttributeTest {

    private static SpanSpecBuilder base() {
        return SpanSpec.builder("test-span")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE);
    }

    // =========================================================================
    // stringListAttribute
    // =========================================================================

    @Test
    void stringList_storesValues() {
        SpanSpec spec = base()
                .stringListAttribute("tags", List.of("alpha", "beta", "gamma"))
                .build();

        assertThat(spec.attributes())
                .containsKey("tags");
        assertThat(spec.attributes().get("tags"))
                .isInstanceOf(SpanSpecAttributeValue.StringListValue.class);

        SpanSpecAttributeValue.StringListValue stored =
                (SpanSpecAttributeValue.StringListValue) spec.attributes().get("tags");
        assertThat(stored.values()).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void stringList_emptyListIsAccepted() {
        SpanSpec spec = base()
                .stringListAttribute("empty-tags", List.of())
                .build();

        assertThat(spec.attributes()).containsKey("empty-tags");
        SpanSpecAttributeValue.StringListValue stored =
                (SpanSpecAttributeValue.StringListValue) spec.attributes().get("empty-tags");
        assertThat(stored.values()).isEmpty();
    }

    @Test
    @SuppressWarnings({"ConstantConditions"})
    void stringList_nullKey_throwsNPE() {
        assertThatThrownBy(() -> base().stringListAttribute(null, List.of("v")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @SuppressWarnings({"ConstantConditions"})
    void stringList_nullList_throwsNPE() {
        assertThatThrownBy(() -> base().stringListAttribute("k", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void stringList_blankKey_throwsIllegalArgument() {
        assertThatThrownBy(() -> base().stringListAttribute("   ", List.of("v")).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void stringList_duplicateKey_throwsIllegalState() {
        assertThatThrownBy(() -> base()
                .stringListAttribute("dup", List.of("x"))
                .stringListAttribute("dup", List.of("y"))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate attribute key");
    }

    @Test
    void stringList_duplicateKeyWithScalar_throwsIllegalState() {
        // Дубликат между scalar и list — тот же guard в putAttribute
        assertThatThrownBy(() -> base()
                .attribute("k", "scalar")
                .stringListAttribute("k", List.of("list"))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate attribute key");
    }

    // =========================================================================
    // longListAttribute
    // =========================================================================

    @Test
    void longList_storesValues() {
        SpanSpec spec = base()
                .longListAttribute("counts", List.of(1L, 42L, Long.MAX_VALUE))
                .build();

        assertThat(spec.attributes()).containsKey("counts");
        SpanSpecAttributeValue.LongListValue stored =
                (SpanSpecAttributeValue.LongListValue) spec.attributes().get("counts");
        assertThat(stored.values()).containsExactly(1L, 42L, Long.MAX_VALUE);
    }

    @Test
    void longList_emptyListIsAccepted() {
        SpanSpec spec = base()
                .longListAttribute("empty-counts", List.of())
                .build();

        SpanSpecAttributeValue.LongListValue stored =
                (SpanSpecAttributeValue.LongListValue) spec.attributes().get("empty-counts");
        assertThat(stored.values()).isEmpty();
    }

    @Test
    @SuppressWarnings({"ConstantConditions"})
    void longList_nullKey_throwsNPE() {
        assertThatThrownBy(() -> base().longListAttribute(null, List.of(1L)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @SuppressWarnings({"ConstantConditions"})
    void longList_nullList_throwsNPE() {
        assertThatThrownBy(() -> base().longListAttribute("k", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void longList_blankKey_throwsIllegalArgument() {
        assertThatThrownBy(() -> base().longListAttribute("\t", List.of(1L)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void longList_duplicateKey_throwsIllegalState() {
        assertThatThrownBy(() -> base()
                .longListAttribute("dup", List.of(1L))
                .longListAttribute("dup", List.of(2L))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate attribute key");
    }

    // =========================================================================
    // doubleListAttribute
    // =========================================================================

    @Test
    void doubleList_storesValues() {
        SpanSpec spec = base()
                .doubleListAttribute("ratios", List.of(0.1, 1.5, 99.9))
                .build();

        assertThat(spec.attributes()).containsKey("ratios");
        SpanSpecAttributeValue.DoubleListValue stored =
                (SpanSpecAttributeValue.DoubleListValue) spec.attributes().get("ratios");
        assertThat(stored.values()).containsExactly(0.1, 1.5, 99.9);
    }

    @Test
    void doubleList_emptyListIsAccepted() {
        SpanSpec spec = base()
                .doubleListAttribute("empty-ratios", List.of())
                .build();

        SpanSpecAttributeValue.DoubleListValue stored =
                (SpanSpecAttributeValue.DoubleListValue) spec.attributes().get("empty-ratios");
        assertThat(stored.values()).isEmpty();
    }

    @Test
    @SuppressWarnings({"ConstantConditions"})
    void doubleList_nullKey_throwsNPE() {
        assertThatThrownBy(() -> base().doubleListAttribute(null, List.of(1.0)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @SuppressWarnings({"ConstantConditions"})
    void doubleList_nullList_throwsNPE() {
        assertThatThrownBy(() -> base().doubleListAttribute("k", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void doubleList_blankKey_throwsIllegalArgument() {
        assertThatThrownBy(() -> base().doubleListAttribute("", List.of(1.0)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void doubleList_duplicateKey_throwsIllegalState() {
        assertThatThrownBy(() -> base()
                .doubleListAttribute("dup", List.of(1.0))
                .doubleListAttribute("dup", List.of(2.0))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate attribute key");
    }

    // =========================================================================
    // booleanListAttribute
    // =========================================================================

    @Test
    void booleanList_storesValues() {
        SpanSpec spec = base()
                .booleanListAttribute("flags", List.of(true, false, true))
                .build();

        assertThat(spec.attributes()).containsKey("flags");
        SpanSpecAttributeValue.BooleanListValue stored =
                (SpanSpecAttributeValue.BooleanListValue) spec.attributes().get("flags");
        assertThat(stored.values()).containsExactly(true, false, true);
    }

    @Test
    void booleanList_emptyListIsAccepted() {
        SpanSpec spec = base()
                .booleanListAttribute("empty-flags", List.of())
                .build();

        SpanSpecAttributeValue.BooleanListValue stored =
                (SpanSpecAttributeValue.BooleanListValue) spec.attributes().get("empty-flags");
        assertThat(stored.values()).isEmpty();
    }

    @Test
    @SuppressWarnings({"ConstantConditions"})
    void booleanList_nullKey_throwsNPE() {
        assertThatThrownBy(() -> base().booleanListAttribute(null, List.of(true)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @SuppressWarnings({"ConstantConditions"})
    void booleanList_nullList_throwsNPE() {
        assertThatThrownBy(() -> base().booleanListAttribute("k", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void booleanList_blankKey_throwsIllegalArgument() {
        assertThatThrownBy(() -> base().booleanListAttribute(" ", List.of(true)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void booleanList_duplicateKey_throwsIllegalState() {
        assertThatThrownBy(() -> base()
                .booleanListAttribute("dup", List.of(true))
                .booleanListAttribute("dup", List.of(false))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate attribute key");
    }

    @Test
    void allFourListTypes_distinctKeys_coexist() {
        SpanSpec spec = base()
                .stringListAttribute("s-list",  List.of("a", "b"))
                .longListAttribute("l-list",    List.of(1L, 2L))
                .doubleListAttribute("d-list",  List.of(0.1, 0.2))
                .booleanListAttribute("b-list", List.of(true, false))
                .build();

        assertThat(spec.attributes()).containsKeys("s-list", "l-list", "d-list", "b-list");
        assertThat(spec.attributes().get("s-list")).isInstanceOf(SpanSpecAttributeValue.StringListValue.class);
        assertThat(spec.attributes().get("l-list")).isInstanceOf(SpanSpecAttributeValue.LongListValue.class);
        assertThat(spec.attributes().get("d-list")).isInstanceOf(SpanSpecAttributeValue.DoubleListValue.class);
        assertThat(spec.attributes().get("b-list")).isInstanceOf(SpanSpecAttributeValue.BooleanListValue.class);
    }

    @Test
    void listAndScalarAttributes_distinctKeys_coexist() {
        SpanSpec spec = base()
                .attribute("scalar-str",  "value")
                .attribute("scalar-long", 42L)
                .stringListAttribute("list-str",  List.of("x"))
                .longListAttribute("list-long",   List.of(1L))
                .build();

        assertThat(spec.attributes()).hasSize(4);
    }
}
