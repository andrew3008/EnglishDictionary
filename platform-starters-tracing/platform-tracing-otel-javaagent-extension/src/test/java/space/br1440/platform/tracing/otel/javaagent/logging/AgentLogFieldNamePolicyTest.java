package space.br1440.platform.tracing.otel.javaagent.logging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLogFieldNamePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"camelCase", "snake_case", "dotted.name", "kebab-name"})
    void valid_field_names_are_accepted(String name) {
        // given
        // when
        // then
        assertThat(AgentLogFieldNamePolicy.isStructurallyValidFieldName(name)).isTrue();
        assertThat(AgentLogFieldNamePolicy.isRejectedFieldName(name)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1name", "has space", "quote\"x"})
    void invalid_field_names_are_rejected(String name) {
        // given
        // when
        // then
        assertThat(AgentLogFieldNamePolicy.isStructurallyValidFieldName(name)).isFalse();
        assertThat(AgentLogFieldNamePolicy.isRejectedFieldName(name)).isTrue();
    }

    @Test
    void field_name_longer_than_limit_is_rejected() {
        // given
        String name = "a".repeat(AgentLogFieldNamePolicy.MAX_FIELD_NAME_CHARS + 1);

        // when
        // then
        assertThat(AgentLogFieldNamePolicy.isStructurallyValidFieldName(name)).isFalse();
    }

    @Test
    void reserved_root_names_are_rejected_case_insensitively() {
        // given
        // when
        // then
        assertThat(AgentLogFieldNamePolicy.isReservedFieldName("Message")).isTrue();
        assertThat(AgentLogFieldNamePolicy.isReservedFieldName("EVENT_CODE")).isTrue();
        assertThat(AgentLogFieldNamePolicy.isRejectedFieldName("error_stacktrace")).isTrue();
    }

    @ParameterizedTest
    @MethodSource("foldingKnownAnswers")
    void sensitive_lookup_folds_separators_without_rewriting_the_name(String name) {
        // given
        // when
        // then
        assertThat(AgentLogFieldNamePolicy.isSensitiveFieldName(name)).isTrue();
        assertThat(AgentLogFieldNamePolicy.canonicalName(name))
                .isEqualTo(AgentLogFieldNamePolicy.canonicalName(name.toLowerCase()));
    }

    @Test
    void whitespace_is_not_folded_for_sensitive_lookup() {
        // given
        // when
        // then
        assertThat(AgentLogFieldNamePolicy.isSensitiveFieldName("api key")).isFalse();
    }

    @Test
    void canonical_collision_rejects_clientsecret_without_separators() {
        // given
        // when
        // then
        assertThat(AgentLogFieldNamePolicy.isRejectedFieldName("clientsecret")).isTrue();
    }

    @Test
    void insertion_order_is_preserved_by_event() {
        // given
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("service", "a");
        fields.put("kind", "b");

        // when
        AgentLogEvent event = new AgentLogEvent(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.INFO,
                AgentEventCodes.BOOT_INSTALLED,
                getClass().getName(),
                "Platform agent logging installed",
                fields,
                null
        );

        // then
        assertThat(event.fields().keySet()).containsExactly("service", "kind");
    }

    @Test
    void constructor_rejects_null_and_oversized_fields_before_copy() {
        // given
        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int i = 0; i < AgentLogFieldNamePolicy.MAX_FIELDS + 1; i++) {
            tooMany.put("f" + i, "v");
        }

        // when
        // then
        assertThatThrownBy(() -> new AgentLogEvent(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.INFO,
                AgentEventCodes.BOOT_INSTALLED,
                getClass().getName(),
                "Platform agent logging installed",
                null,
                null
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentLogEvent(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.INFO,
                AgentEventCodes.BOOT_INSTALLED,
                getClass().getName(),
                "Platform agent logging installed",
                tooMany,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fields");
    }

    private static Stream<Arguments> foldingKnownAnswers() {
        return Stream.of(
                Arguments.of("api-key"),
                Arguments.of("api_key"),
                Arguments.of("apikey"),
                Arguments.of("e-mail"),
                Arguments.of("email"),
                Arguments.of("set-cookie"),
                Arguments.of("set_cookie"),
                Arguments.of("x-auth-header"),
                Arguments.of("x_auth_header")
        );
    }
}
