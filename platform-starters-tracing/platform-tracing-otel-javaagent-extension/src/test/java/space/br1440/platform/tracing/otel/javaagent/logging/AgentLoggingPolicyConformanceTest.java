package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;


class AgentLoggingPolicyConformanceTest {

    private final PlatformLogEventEncoder encoder = new PlatformLogEventEncoder();

    @Test
    void field_insertion_order_is_byte_stable() {
        // given
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("service", "a");
        fields.put("kind", "b");
        AgentLogRecord event = new AgentLogRecord(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.INFO,
                AgentEventCode.BOOT_INSTALLED,
                "EXTENSION_INSTALLED",
                getClass().getName(),
                "Platform agent logging installed",
                fields,
                null,
                null
        );

        // when
        byte[] left = encoder.encode(event);
        byte[] right = encoder.encode(event);

        // then
        assertThat(left).isEqualTo(right);
        assertThat(new String(left, StandardCharsets.UTF_8))
                .contains("\"service\":\"a\",\"kind\":\"b\"");
    }

    @Test
    void encoded_payload_never_exceeds_max_bytes() {
        // given
        AgentLogRecord event = byteBudgetEvent();

        // when
        byte[] encoded = encoder.encode(event);

        // then
        assertThat(encoded.length).isLessThanOrEqualTo(AgentLogRecord.MAX_ENCODED_BYTES);
        assertThat(encoded[encoded.length - 1]).isEqualTo((byte) '\n');
        assertThat(new String(encoded, StandardCharsets.UTF_8)).contains("PTA-ENCODE-001");
    }

    @Test
    void argument_values_are_truncated_and_control_chars_are_replaced() {
        // given
        String raw = "a".repeat(AgentLogRecord.MAX_ARGUMENT_CHARS + 16) + "\r\n\u001B";

        // when
        String sanitized = AgentLogTextSanitizer.normalizeBounded(raw, AgentLogRecord.MAX_ARGUMENT_CHARS);

        // then
        assertThat(sanitized).hasSize(AgentLogRecord.MAX_ARGUMENT_CHARS);
        assertThat(sanitized).doesNotContain("\r", "\n", "\u001B");
    }

    @Test
    void throwable_traversal_is_bounded() {
        // given
        Throwable current = new IllegalStateException("secret");
        for (int i = 0; i < 10; i++) {
            current = new IllegalStateException("secret", current);
        }
        current.addSuppressed(new IllegalArgumentException("suppressed-1"));
        current.addSuppressed(new IllegalArgumentException("suppressed-2"));
        current.addSuppressed(new IllegalArgumentException("suppressed-3"));
        current.addSuppressed(new IllegalArgumentException("suppressed-4"));
        clearStacks(current);

        // when
        String rendered = BoundedThrowableRenderer.render(current);

        // then
        assertThat(rendered).doesNotContain("secret");
        assertThat(rendered).contains("cause chain truncated");
        assertThat(rendered.lines().filter(line -> line.contains("suppressed")).count())
                .isEqualTo(BoundedThrowableRenderer.MAX_SUPPRESSED);
    }

    @Test
    void oversized_logger_is_rejected_by_the_event() {
        // given
        String hugeLogger = "x".repeat(20_000);

        // when
        // then
        assertThatThrownBy(() -> new AgentLogRecord(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.WARN,
                AgentEventCode.SCRUB_FAILURE,
                "SCRUBBING_BOUNDARY_COLLAPSED",
                hugeLogger,
                "Scrubbing boundary collapsed",
                Map.of(),
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class).hasMessage("logger");
    }

    @Test
    void byte_budget_fallback_keeps_the_original_event_code() {
        // given
        // when
        String json = new String(encoder.encode(byteBudgetEvent()), StandardCharsets.UTF_8);

        // then
        assertThat(json).contains("\"event_code\":\"PTA-ENCODE-001\"");
        assertThat(json).contains("\"original_event_code\":\"PTA-EXPORT-001\"");
        assertThat(json).contains("\"original_event_name\":\"SAFE_EXPORT_THROWN\"");
        assertThat(json).contains("\"fallback_reason\":\"byte_budget\"");
        assertThat(json).doesNotContain("invariant_violation");
    }

    @Test
    void encoder_escapes_quotes_and_keeps_truncation_deterministic() {
        // given
        AgentLogRecord first = byteBudgetEvent();
        AgentLogRecord second = byteBudgetEvent();

        // when
        byte[] left = encoder.encode(first);
        byte[] right = encoder.encode(second);

        // then
        assertThat(left).isEqualTo(right);
        assertThat(new String(left, StandardCharsets.UTF_8)).contains("PTA-ENCODE-001");
        assertThat(encoder.encode(quoted())).asString(StandardCharsets.UTF_8).contains("\\\"");
    }

    private static void clearStacks(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            current.setStackTrace(new StackTraceElement[0]);
            for (Throwable suppressed : current.getSuppressed()) {
                suppressed.setStackTrace(new StackTraceElement[0]);
            }
            current = current.getCause();
        }
    }

    private static AgentLogRecord byteBudgetEvent() {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < AgentLogRecord.MAX_FIELDS; i++) {
            fields.put("f" + i, "Q".repeat(AgentLogRecord.MAX_ARGUMENT_CHARS));
        }
        return new AgentLogRecord(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.ERROR,
                AgentEventCode.EXPORT_SAFE_EXPORTER,
                "SAFE_EXPORT_THROWN",
                AgentLoggingPolicyConformanceTest.class.getName(),
                "quote\"x",
                fields,
                null,
                null
        );
    }

    private static AgentLogRecord quoted() {
        return new AgentLogRecord(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.ERROR,
                AgentEventCode.EXPORT_SAFE_EXPORTER,
                "SAFE_EXPORT_THROWN",
                AgentLoggingPolicyConformanceTest.class.getName(),
                "quote\"x",
                Map.of(),
                null,
                null
        );
    }
}
