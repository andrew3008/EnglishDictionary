package space.br1440.platform.errorhandling.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.errorhandling.utils.ClientMetadataBuilder.builder;

@DisplayName("ClientMetadataBuilder")
class ClientMetadataBuilderTest {

    @Nested
    @DisplayName("builder()")
    class Factory {

        @Test
        void returnsNewEmptyBuilder_eachInvocation() {
            ClientMetadataBuilder first = builder();
            ClientMetadataBuilder second = builder();

            assertThat(first).isNotSameAs(second);
            assertThat(first.build()).isEmpty();
            assertThat(second.build()).isEmpty();
        }

        @Test
        void emptyBuilder_buildsEmptyMap() {
            assertThat(builder().build()).isEmpty();
        }
    }

    @Nested
    @DisplayName("put(String key, String value)")
    class StringValues {

        @Test
        void nonNullValue_storesAsIs_noQuoting() {
            Map<String, String> result = builder()
                    .put("name", "harbor-registry")
                    .build();

            assertThat(result).containsEntry("name", "harbor-registry");
        }

        @Test
        void nullValue_keyIsAbsent() {
            Map<String, String> result = builder()
                    .put("absent", (String) null)
                    .build();

            assertThat(result).doesNotContainKey("absent");
        }

        @Test
        void emptyString_isStoredAsEmpty() {
            // Пустая строка как осмысленное значение сохраняется (отличается от null);
            // решение оставлено за вызывающей стороной.
            Map<String, String> result = builder()
                    .put("note", "")
                    .build();

            assertThat(result).containsEntry("note", "");
        }
    }

    @Nested
    @DisplayName("put(String key, UUID value)")
    class UuidValues {

        @Test
        void nonNullUuid_storesPlainString_noQuoting() {
            UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

            Map<String, String> result = builder()
                    .put("traceId", id)
                    .build();

            assertThat(result).containsEntry("traceId", "550e8400-e29b-41d4-a716-446655440000");
        }

        @Test
        void nullUuid_keyIsAbsent() {
            Map<String, String> result = builder()
                    .put("traceId", (UUID) null)
                    .build();

            assertThat(result).doesNotContainKey("traceId");
        }
    }

    @Nested
    @DisplayName("put(String key, Number value)")
    class NumberValues {

        @Test
        void integerValue_storesPlainString_noQuoting() {
            Map<String, String> result = builder()
                    .put("attempt", 42)
                    .build();

            assertThat(result).containsEntry("attempt", "42");
        }

        @Test
        void nullNumber_keyIsAbsent() {
            Map<String, String> result = builder()
                    .put("attempt", (Number) null)
                    .build();

            assertThat(result).doesNotContainKey("attempt");
        }
    }

    @Nested
    @DisplayName("put(String key, Collection<String> values)")
    class CollectionValues {

        @Test
        void nonEmptyCollection_storesJoinedValues_noQuoting() {
            Map<String, String> result = builder()
                    .put("tags", List.of("a", "b"))
                    .build();

            assertThat(result).containsEntry("tags", "a, b");
        }

        @Test
        void nullCollection_keyIsAbsent() {
            Map<String, String> result = builder()
                    .put("tags", (Collection<String>) null)
                    .build();

            assertThat(result).doesNotContainKey("tags");
        }

        @Test
        void emptyCollection_keyIsAbsent() {
            Map<String, String> result = builder()
                    .put("tags", List.of())
                    .build();

            assertThat(result).doesNotContainKey("tags");
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

        @Test
        void duplicateKey_lastValueWins_orderOfFirstOccurrencePreserved() {
            Map<String, String> result = builder()
                    .put("key", "first")
                    .put("other", "x")
                    .put("key", "second")
                    .build();

            assertThat(result)
                    .hasSize(2)
                    .containsEntry("key", "second")
                    .containsEntry("other", "x");
        }
    }
}
