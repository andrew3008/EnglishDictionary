package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CauseMetadataEnricher")
class CauseMetadataEnricherTest {

    @Nested
    @DisplayName("enrichWithRootCause (полная сигнатура)")
    class EnrichWithRootCauseFull {

        @Test
        void doesNothingWhenNoCause() {
            // given
            RuntimeException root = new RuntimeException("msg");
            Map<String, String> meta = new HashMap<>();

            // when
            CauseMetadataEnricher.enrichWithRootCause(root, meta, 10, 200);

            // then
            assertTrue(meta.isEmpty());
        }

        @Test
        void addsRootCauseClassWhenChained() {
            // given
            IllegalStateException deepest = new IllegalStateException("deep-msg");
            RuntimeException wrapper = new RuntimeException("wrapper-msg", deepest);
            Map<String, String> meta = new HashMap<>();

            // when
            CauseMetadataEnricher.enrichWithRootCause(wrapper, meta, 10, 200);

            // then
            assertThat(meta.get("rootCauseClass")).isEqualTo(IllegalStateException.class.getName());
        }

        @Test
        void addsRootCauseMessageWhenDifferentFromCause() {
            // given
            IllegalStateException deepest = new IllegalStateException("deep-msg");
            RuntimeException wrapper = new RuntimeException("wrapper-msg", deepest);
            Map<String, String> meta = new HashMap<>();

            // when
            CauseMetadataEnricher.enrichWithRootCause(wrapper, meta, 10, 200);

            // then
            assertThat(meta.get("rootCauseMessage")).isEqualTo("deep-msg");
        }

        @Test
        void omitsRootCauseMessageWhenSameAsCause() {
            // given
            IllegalStateException deepest = new IllegalStateException("same-msg");
            RuntimeException wrapper = new RuntimeException("same-msg", deepest);
            Map<String, String> meta = new HashMap<>();

            // when
            CauseMetadataEnricher.enrichWithRootCause(wrapper, meta, 10, 200);

            // then
            assertThat(meta.get("rootCauseClass")).isEqualTo(IllegalStateException.class.getName());
            assertFalse(meta.containsKey("rootCauseMessage"));
        }

        @Test
        void omitsRootCauseMessageWhenNull() {
            // given
            IllegalStateException deepest = new IllegalStateException((String) null);
            RuntimeException wrapper = new RuntimeException("wrapper-msg", deepest);
            Map<String, String> meta = new HashMap<>();

            // when
            CauseMetadataEnricher.enrichWithRootCause(wrapper, meta, 10, 200);

            // then
            assertFalse(meta.containsKey("rootCauseMessage"));
        }

        @Test
        void truncatesLongRootCauseMessage() {
            // given
            String longMsg = "x".repeat(500);
            IllegalStateException deepest = new IllegalStateException(longMsg);
            RuntimeException wrapper = new RuntimeException("wrapper", deepest);
            Map<String, String> meta = new HashMap<>();

            // when
            CauseMetadataEnricher.enrichWithRootCause(wrapper, meta, 10, 100);

            // then
            String recorded = meta.get("rootCauseMessage");
            assertThat(recorded).isNotNull().startsWith("x".repeat(100)).contains("[TRUNCATED]");
        }

        @Test
        void respectsMaxDepthCutoff() {
            // given
            RuntimeException deepest = new RuntimeException("deepest");
            RuntimeException mid = new RuntimeException("mid", deepest);
            RuntimeException top = new RuntimeException("top", mid);
            Map<String, String> meta = new HashMap<>();

            // when
            CauseMetadataEnricher.enrichWithRootCause(top, meta, 2, 200);

            // then
            assertThat(meta.get("rootCauseClass")).isEqualTo(RuntimeException.class.getName());
            assertThat(meta.get("rootCauseMessage")).isEqualTo("mid");
        }
    }

    @Nested
    @DisplayName("enrichWithRootCause (maxMessageLength + DIAGNOSTIC_CAUSE_CHAIN_DEPTH)")
    class EnrichWithRootCauseDefaultDepth {

        @Test
        void twoArgOverloadMatchesExplicitDiagnosticDepthConstant() {
            // given
            RuntimeException d4 = new RuntimeException("d4");
            RuntimeException d3 = new RuntimeException("d3", d4);
            RuntimeException d2 = new RuntimeException("d2", d3);
            RuntimeException top = new RuntimeException("top", d2);

            // when
            Map<String, String> shortSig = new HashMap<>();
            CauseMetadataEnricher.enrichWithRootCause(top, shortSig, 200);

            Map<String, String> explicit = new HashMap<>();
            CauseMetadataEnricher.enrichWithRootCause(top, explicit, CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH, 200);

            // then
            assertThat(shortSig).isEqualTo(explicit);
        }
    }

    @Nested
    @DisplayName("enrichWithNestedCause")
    class EnrichWithNestedCause {

        @Test
        void doesNothingWhenNoCause() {
            // given
            RuntimeException root = new RuntimeException("msg");
            Map<String, String> meta = new HashMap<>();

            // when
            CauseMetadataEnricher.enrichWithNestedCause(root, meta, 200);

            // then
            assertTrue(meta.isEmpty());
        }

        @Test
        void addsNestedCauseClass() {
            // given
            IllegalArgumentException nested = new IllegalArgumentException("nested-msg");
            RuntimeException wrapper = new RuntimeException("wrapper", nested);
            Map<String, String> meta = new HashMap<>();

            // when
            CauseMetadataEnricher.enrichWithNestedCause(wrapper, meta, 200);

            // then
            assertThat(meta.get("nestedCauseClass")).isEqualTo(IllegalArgumentException.class.getName());
        }

        @Test
        void addsNestedCauseMessage() {
            // given
            IllegalArgumentException nested = new IllegalArgumentException("nested-msg");
            RuntimeException wrapper = new RuntimeException("wrapper", nested);
            Map<String, String> meta = new HashMap<>();

            // when
            CauseMetadataEnricher.enrichWithNestedCause(wrapper, meta, 200);

            // then
            assertThat(meta.get("nestedCauseMessage")).isEqualTo("nested-msg");
        }

        @Test
        void omitsNestedCauseMessageWhenNull() {
            // given
            IllegalArgumentException nested = new IllegalArgumentException((String) null);
            RuntimeException wrapper = new RuntimeException("wrapper", nested);
            Map<String, String> meta = new HashMap<>();

            // when
            CauseMetadataEnricher.enrichWithNestedCause(wrapper, meta, 200);

            // then
            assertThat(meta.get("nestedCauseClass")).isEqualTo(IllegalArgumentException.class.getName());
            assertFalse(meta.containsKey("nestedCauseMessage"));
        }

        @Test
        void truncatesLongNestedCauseMessage() {
            // given
            String longMsg = "y".repeat(300);
            IllegalArgumentException nested = new IllegalArgumentException(longMsg);
            RuntimeException wrapper = new RuntimeException("wrapper", nested);
            Map<String, String> meta = new HashMap<>();

            // when
            CauseMetadataEnricher.enrichWithNestedCause(wrapper, meta, 50);

            // then
            assertThat(meta.get("nestedCauseMessage")).startsWith("y".repeat(50)).contains("[TRUNCATED]");
        }

        @Test
        void onlyLooksAtImmediateNestedCause() {
            // given
            RuntimeException deepest = new RuntimeException("deepest");
            RuntimeException mid = new RuntimeException("mid", deepest);
            RuntimeException top = new RuntimeException("top", mid);
            Map<String, String> meta = new HashMap<>();

            // when
            CauseMetadataEnricher.enrichWithNestedCause(top, meta, 200);

            // then
            assertThat(meta.get("nestedCauseClass")).isEqualTo(RuntimeException.class.getName());
            assertThat(meta.get("nestedCauseMessage")).isEqualTo("mid");
        }
    }

}
