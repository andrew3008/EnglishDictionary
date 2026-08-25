package space.br1440.platform.tracing.otel.javaagent.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformAgentLoggerTest {

    private final AgentLogCapture capture = new AgentLogCapture();
    private PlatformAgentLogger logger;

    @BeforeEach
    void installCapture() {
        capture.install();
        capture.applyLevel(AgentLogLevel.INFO);
        logger = PlatformAgentLogger.get(PlatformAgentLoggerTest.class);
    }

    @AfterEach
    void restore() {
        capture.restore();
    }

    @Test
    void disabled_debug_does_not_construct_an_event() {
        // given
        // when
        EmitResult result = logger.debug(AgentEventCodes.BOOT_HOSTNAME_FAILED, "Hostname resolution failed",
                "detail", "unused");

        // then
        assertThat(result).isEqualTo(EmitResult.FILTERED_BY_LEVEL);
        assertThat(capture.events()).isEmpty();
    }

    @Test
    void off_cannot_be_used_as_an_event_level() {
        // given
        // when
        // then
        assertThatThrownBy(() -> logger.emit(AgentLogLevel.OFF, AgentEventCodes.BOOT_INSTALLED,
                "Platform agent logging installed", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OFF is not a valid event level");
        assertThat(capture.events()).isEmpty();
    }

    @Test
    void rejects_forbidden_field_name() {
        // given
        // when
        EmitResult result = logger.warn(AgentEventCodes.CTRL_UPDATE, "Tracing settings update failed",
                "secret", "value");

        // then
        assertThat(result).isEqualTo(EmitResult.REJECTED_BY_SECURITY_POLICY);
        assertThat(capture.events()).isEmpty();
    }

    @Test
    void rejects_blank_message() {
        // given
        // when
        EmitResult result = logger.warn(AgentEventCodes.CTRL_UPDATE, "   ");

        // then
        assertThat(result).isEqualTo(EmitResult.REJECTED_BY_SECURITY_POLICY);
        assertThat(capture.events()).isEmpty();
    }

    @Test
    void rejects_reserved_root_field_name() {
        // given
        // when
        EmitResult result = logger.warn(AgentEventCodes.CTRL_UPDATE, "Tracing settings update failed",
                "message", "duplicate");

        // then
        assertThat(result).isEqualTo(EmitResult.REJECTED_BY_SECURITY_POLICY);
        assertThat(capture.events()).isEmpty();
    }

    @Test
    void sanitizes_control_characters_in_fields() {
        // given
        // when
        EmitResult result = logger.warn(AgentEventCodes.CTRL_WRITER, "Private control writer failed",
                "attemptId", "id\r\nforged");

        // then
        assertThat(result).isEqualTo(EmitResult.EMITTED);
        assertThat(capture.events()).singleElement().satisfies(event ->
                assertThat(event.fields().get("attemptId")).isEqualTo("id??forged"));
    }

    @Test
    void rejects_unknown_event_code() {
        // given
        // when
        EmitResult result = logger.warn("FAKE-UNKNOWN-999", "Tracing settings update failed");

        // then
        assertThat(result).isEqualTo(EmitResult.REJECTED_BY_SECURITY_POLICY);
        assertThat(capture.events()).isEmpty();
    }

    @Test
    void rejects_odd_field_list() {
        // given
        // when
        EmitResult result = logger.warn(AgentEventCodes.CTRL_UPDATE, "Tracing settings update failed",
                "kind");

        // then
        assertThat(result).isEqualTo(EmitResult.REJECTED_BY_SECURITY_POLICY);
        assertThat(capture.events()).isEmpty();
    }

    @Test
    void rejects_duplicate_field_names_case_insensitively() {
        // given
        // when
        EmitResult result = logger.warn(AgentEventCodes.CTRL_UPDATE, "Tracing settings update failed",
                "kind", "a", "Kind", "b");

        // then
        assertThat(result).isEqualTo(EmitResult.REJECTED_BY_SECURITY_POLICY);
        assertThat(capture.events()).isEmpty();
    }

    @Test
    void rejects_field_count_overflow() {
        // given
        Object[] fields = new Object[AgentLogFieldNamePolicy.MAX_FIELDS * 2 + 2];
        for (int i = 0; i < fields.length; i += 2) {
            fields[i] = "f" + (i / 2);
            fields[i + 1] = "v";
        }

        // when
        EmitResult result = logger.warn(AgentEventCodes.CTRL_UPDATE, "Tracing settings update failed",
                fields);

        // then
        assertThat(result).isEqualTo(EmitResult.REJECTED_BY_SECURITY_POLICY);
        assertThat(capture.events()).isEmpty();
    }

    @Test
    void rejects_invalid_field_name_syntax() {
        // given
        // when
        EmitResult result = logger.warn(AgentEventCodes.CTRL_UPDATE, "Tracing settings update failed",
                "1name", "value");

        // then
        assertThat(result).isEqualTo(EmitResult.REJECTED_BY_SECURITY_POLICY);
        assertThat(capture.events()).isEmpty();
    }
}
