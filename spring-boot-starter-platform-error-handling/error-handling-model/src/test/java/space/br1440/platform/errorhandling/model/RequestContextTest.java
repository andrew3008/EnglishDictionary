package space.br1440.platform.errorhandling.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RequestContext")
class RequestContextTest {

    @Nested
    @DisplayName("toMap")
    class ToMap {

        @Test
        @DisplayName("all fields set -> map contains all three entries")
        void allFieldsSet_containsAllEntries() {
            RequestContext ctx = RequestContext.builder()
                    .correlationId("corr-1")
                    .traceId("trace-2")
                    .spanId("span-3")
                    .build();

            Map<String, String> map = ctx.toMap();

            assertThat(map)
                    .hasSize(3)
                    .containsEntry("correlationId", "corr-1")
                    .containsEntry("traceId", "trace-2")
                    .containsEntry("spanId", "span-3");
        }

        @Test
        @DisplayName("null fields are excluded from map")
        void nullFields_excluded() {
            RequestContext ctx = RequestContext.builder()
                    .correlationId("corr-1")
                    .build();

            Map<String, String> map = ctx.toMap();

            assertThat(map)
                    .hasSize(1)
                    .containsEntry("correlationId", "corr-1")
                    .doesNotContainKey("traceId")
                    .doesNotContainKey("spanId");
        }

        @Test
        @DisplayName("blank fields are excluded from map")
        void blankFields_excluded() {
            RequestContext ctx = RequestContext.builder()
                    .correlationId("  ")
                    .traceId("")
                    .spanId("span-ok")
                    .build();

            Map<String, String> map = ctx.toMap();

            assertThat(map)
                    .hasSize(1)
                    .containsEntry("spanId", "span-ok");
        }

        @Test
        @DisplayName("all null -> empty unmodifiable map")
        void allNull_emptyUnmodifiableMap() {
            RequestContext ctx = RequestContext.builder().build();

            Map<String, String> map = ctx.toMap();

            assertThat(map).isEmpty();
            assertThatThrownBy(() -> map.put("key", "val"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("withCorrelationIdIfMissing")
    class WithCorrelationIdIfMissing {

        @Test
        @DisplayName("correlationId already set -> returns same instance")
        void alreadySet_returnsSameInstance() {
            RequestContext ctx = RequestContext.builder()
                    .correlationId("existing")
                    .traceId("t")
                    .build();

            RequestContext result = ctx.withCorrelationIdIfMissing("fallback");

            assertThat(result).isSameAs(ctx);
            assertThat(result.correlationId()).isEqualTo("existing");
        }

        @Test
        @DisplayName("correlationId null -> copies with provided value")
        void nullCorrelationId_substitutes() {
            RequestContext ctx = RequestContext.builder()
                    .traceId("t1")
                    .spanId("s1")
                    .build();

            RequestContext result = ctx.withCorrelationIdIfMissing("generated-uuid");

            assertThat(result).isNotSameAs(ctx);
            assertThat(result.correlationId()).isEqualTo("generated-uuid");
            assertThat(result.traceId()).isEqualTo("t1");
            assertThat(result.spanId()).isEqualTo("s1");
        }

        @Test
        @DisplayName("correlationId blank -> copies with provided value")
        void blankCorrelationId_substitutes() {
            RequestContext ctx = new RequestContext("   ", "t", "s");

            RequestContext result = ctx.withCorrelationIdIfMissing("fallback");

            assertThat(result.correlationId()).isEqualTo("fallback");
        }
    }
}
