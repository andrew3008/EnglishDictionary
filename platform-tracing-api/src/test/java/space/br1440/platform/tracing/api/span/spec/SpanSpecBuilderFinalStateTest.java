package space.br1440.platform.tracing.api.span.spec;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanSpecBuilderFinalStateTest {

    private static final SpanLinkContext LINK =
            SpanLinkContext.sampled("01234567890123456789012345678901", "0123456789012345");

    private SpanSpecBuilder base() {
        return SpanSpec.builder("test-span")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE);
    }

    @Test
    void rootThenLinkedTo_isValid() {
        SpanSpec spec = base().root().linkedTo(LINK).build();

        assertThat(spec.relationship().kind()).isEqualTo(SpanRelationship.ROOT);
        assertThat(spec.relationship().links()).containsExactly(LINK);
    }

    @Test
    void linkedToThenRoot_isValid() {
        SpanSpec spec = base().linkedTo(LINK).root().build();

        assertThat(spec.relationship().kind()).isEqualTo(SpanRelationship.ROOT);
        assertThat(spec.relationship().links()).containsExactly(LINK);
    }

    @Test
    void detachedThenLinkedTo_isInvalid() {
        assertThatThrownBy(() -> base().detached().linkedTo(LINK).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
    }

    @Test
    void linkedToThenDetached_isInvalid() {
        assertThatThrownBy(() -> base().linkedTo(LINK).detached().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
    }

    @Test
    void childThenLinkedTo_isInvalid() {
        assertThatThrownBy(() -> base().child().linkedTo(LINK).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
    }

    @Test
    void linkedToThenChild_isInvalid() {
        assertThatThrownBy(() -> base().linkedTo(LINK).child().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
    }

    @Test
    void childThenRoot_isInvalid() {
        assertThatThrownBy(() -> base().child().root().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relationship already set");
    }

    @Test
    void rootThenDetached_isInvalid() {
        assertThatThrownBy(() -> base().root().detached().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relationship already set");
    }

    @Test
    void repeatedReason_isInvalid() {
        assertThatThrownBy(() -> base()
                .reason(SpanSpecReason.LEGACY_INTEGRATION)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void repeatedReference_isInvalid() {
        assertThatThrownBy(() -> base()
                .reference("JIRA-1")
                .reference("JIRA-2")
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reference");
    }

    @Test
    void duplicateAttributeKey_isInvalid() {
        assertThatThrownBy(() -> base()
                .attribute("k", "a")
                .attribute("k", "b")
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate attribute key");
    }

    @Test
    void repeatedLinkedTo_isAdditive() {
        SpanLinkContext second = SpanLinkContext.sampled(
                "fedcba9876543210fedcba9876543210fe",
                "fedcba9876543210");

        SpanSpec spec = base().root().linkedTo(LINK).linkedTo(second).build();

        assertThat(spec.relationship().links()).containsExactly(LINK, second);
    }

    @Test
    void temporaryWorkaroundWithoutReference_isInvalid() {
        assertThatThrownBy(() -> SpanSpec.builder("w")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.TEMPORARY_WORKAROUND)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEMPORARY_WORKAROUND");
    }

    @Test
    void temporaryWorkaroundWithReference_isValid() {
        SpanSpec spec = SpanSpec.builder("w")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.TEMPORARY_WORKAROUND)
                .reference("JIRA-123")
                .build();

        assertThat(spec.reason()).isEqualTo(SpanSpecReason.TEMPORARY_WORKAROUND);
        assertThat(spec.reference()).contains("JIRA-123");
    }

    @Test
    void forbiddenCatchAllReasonValues_areAbsent() {
        for (String forbidden : List.of("OTHER", "UNKNOWN", "CUSTOM", "MISC")) {
            assertThatThrownBy(() -> SpanSpecReason.valueOf(forbidden))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void categoryIsRequired() {
        assertThatThrownBy(() -> SpanSpec.builder("x")
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("category");
    }

    @Test
    void reasonIsRequired() {
        assertThatThrownBy(() -> SpanSpec.builder("x")
                .category(SpanCategory.INTERNAL)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void defaultRelationshipIsChildWithoutExplicitSetter() {
        SpanSpec spec = base().build();

        assertThat(spec.relationship().kind()).isEqualTo(SpanRelationship.CHILD);
        assertThat(spec.relationship().links()).isEmpty();
    }
}
