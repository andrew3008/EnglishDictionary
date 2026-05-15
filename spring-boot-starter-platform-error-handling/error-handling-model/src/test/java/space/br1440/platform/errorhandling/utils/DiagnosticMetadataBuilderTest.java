package space.br1440.platform.errorhandling.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.errorhandling.utils.DiagnosticMetadataBuilder.builder;

@DisplayName("DiagnosticMetadataBuilder")
class DiagnosticMetadataBuilderTest {

    @Nested
    @DisplayName("API parity with ClientMetadataBuilder")
    class ApiParity {

        @Test
        void stringValue_storedAsIs() {
            Map<String, String> result = builder()
                    .put("causeClass", "java.sql.SQLException")
                    .build();

            assertThat(result).containsEntry("causeClass", "java.sql.SQLException");
        }

        @Test
        void numberValue_storedAsPlainString() {
            Map<String, String> result = builder()
                    .put("sqlErrorCode", 23505)
                    .build();

            assertThat(result).containsEntry("sqlErrorCode", "23505");
        }

        @Test
        void uuidValue_storedAsPlainString() {
            UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

            Map<String, String> result = builder()
                    .put("internalCorrelationId", id)
                    .build();

            assertThat(result).containsEntry("internalCorrelationId", id.toString());
        }

        @Test
        void collectionValue_joinedWithComma() {
            Map<String, String> result = builder()
                    .put("stackHint", List.of("frame1", "frame2"))
                    .build();

            assertThat(result).containsEntry("stackHint", "frame1, frame2");
        }
    }

    @Nested
    @DisplayName("Skip-on-null / skip-on-empty semantics")
    class SkipSemantics {

        @Test
        void nullString_keyIsAbsent() {
            Map<String, String> result = builder()
                    .put("k", (String) null)
                    .build();

            assertThat(result).doesNotContainKey("k");
        }

        @Test
        void nullCollection_keyIsAbsent() {
            Map<String, String> result = builder()
                    .put("stackHint", (Collection<String>) null)
                    .build();

            assertThat(result).doesNotContainKey("stackHint");
        }

        @Test
        void emptyCollection_keyIsAbsent() {
            Map<String, String> result = builder()
                    .put("stackHint", List.of())
                    .build();

            assertThat(result).doesNotContainKey("stackHint");
        }
    }

    @Nested
    @DisplayName("build()")
    class Build {

        @Test
        void returnsUnmodifiableMap() {
            Map<String, String> result = builder()
                    .put("k", "v")
                    .build();

            assertThat(result).isUnmodifiable();
        }

        @Test
        void emptyBuilder_producesEmptyMap() {
            Map<String, String> result = builder().build();

            assertThat(result).isEmpty();
        }

        @Test
        void preservesInsertionOrder() {
            Map<String, String> result = builder()
                    .put("c", "3")
                    .put("a", "1")
                    .put("b", "2")
                    .build();

            assertThat(result).containsExactly(
                    Map.entry("c", "3"),
                    Map.entry("a", "1"),
                    Map.entry("b", "2"));
        }
    }
}
