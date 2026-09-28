package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentLogTextSanitizerTest {

    @Test
    void safe_short_value_is_returned_as_the_same_instance() {
        // given
        String safe = "resource-identity";

        // when
        String normalized = AgentLogTextSanitizer.normalizeBounded(safe, AgentLogRecord.MAX_ARGUMENT_CHARS);

        // then
        assertThat(normalized).isSameAs(safe);
    }

    @Test
    void safe_supplementary_unicode_is_returned_as_the_same_instance() {
        // given
        String safe = "ok \uD83D\uDE00";

        // when
        String normalized = AgentLogTextSanitizer.normalizeBounded(safe, AgentLogRecord.MAX_ARGUMENT_CHARS);

        // then
        assertThat(normalized).isSameAs(safe);
    }

    @Test
    void null_field_value_becomes_empty() {
        // given
        // when
        String normalized = AgentLogTextSanitizer.normalizeBounded(null, AgentLogRecord.MAX_ARGUMENT_CHARS);

        // then
        assertThat(normalized).isEmpty();
    }

    @Test
    void control_and_bidi_characters_are_replaced() {
        // given
        String raw = "a\r\n\t\u001B\u007F\u0085\u061C\u200E\u2028\u202A\u2066z";

        // when
        String normalized = AgentLogTextSanitizer.normalizeBounded(raw, AgentLogRecord.MAX_ARGUMENT_CHARS);

        // then
        assertThat(normalized).isEqualTo("a???????????z");
    }

    @Test
    void right_to_left_override_is_wire_unsafe_and_replaced() {
        // given
        String raw = "safe\u202Emalicious";

        // when
        String normalized = AgentLogTextSanitizer.normalizeBounded(raw, AgentLogRecord.MAX_ARGUMENT_CHARS);

        // then
        assertThat(AgentLogTextSanitizer.isWireUnsafe(raw)).isTrue();
        assertThat(normalized).isEqualTo("safe?malicious");
        assertThat(normalized).doesNotContain("\u202E");
    }

    @Test
    void isolated_surrogates_are_replaced_with_fffd() {
        // given
        String raw = "a\uD83D" + "b\uDE00c";

        // when
        String normalized = AgentLogTextSanitizer.normalizeBounded(raw, AgentLogRecord.MAX_ARGUMENT_CHARS);

        // then
        assertThat(normalized).isEqualTo("a\uFFFDb\uFFFDc");
    }

    @Test
    void truncation_does_not_split_a_surrogate_pair() {
        // given
        String pair = "\uD83D\uDE00";
        String raw = "x".repeat(AgentLogRecord.MAX_ARGUMENT_CHARS - 1) + pair;

        // when
        String normalized = AgentLogTextSanitizer.normalizeBounded(raw, AgentLogRecord.MAX_ARGUMENT_CHARS);

        // then
        assertThat(normalized).hasSize(AgentLogRecord.MAX_ARGUMENT_CHARS - 1);
        assertThat(normalized).doesNotContain(pair);
        assertThat(normalized).doesNotContain("\uD83D");
    }

    @Test
    void oversized_value_is_truncated_without_scanning_as_identity() {
        // given
        String raw = "a".repeat(AgentLogRecord.MAX_ARGUMENT_CHARS + 16) + "\r\n";

        // when
        String normalized = AgentLogTextSanitizer.normalizeBounded(raw, AgentLogRecord.MAX_ARGUMENT_CHARS);

        // then
        assertThat(normalized).hasSize(AgentLogRecord.MAX_ARGUMENT_CHARS);
        assertThat(normalized).doesNotContain("\r", "\n");
    }

    @Test
    void logger_uses_logger_bound() {
        // given
        String raw = "l".repeat(AgentLogRecord.MAX_LOGGER_CHARS + 8);

        // when
        String normalized = AgentLogTextSanitizer.normalizeBounded(raw, AgentLogRecord.MAX_LOGGER_CHARS);

        // then
        assertThat(normalized).hasSize(AgentLogRecord.MAX_LOGGER_CHARS);
    }

    @Test
    void bounded_engine_accepts_a_caller_specific_limit() {
        // given
        String pair = "\uD83D\uDE00";
        String raw = "xy" + pair;

        // when
        String normalized = AgentLogTextSanitizer.normalizeBounded(raw, 3);

        // then
        assertThat(normalized).isEqualTo("xy");
        assertThat(normalized).doesNotContain(pair);
    }
}
