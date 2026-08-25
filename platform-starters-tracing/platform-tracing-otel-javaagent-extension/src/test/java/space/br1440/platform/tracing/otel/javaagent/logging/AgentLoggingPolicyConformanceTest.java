package space.br1440.platform.tracing.otel.javaagent.logging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoggingPolicyConformanceTest {

    private final PlatformLogEventEncoder encoder = new PlatformLogEventEncoder();

    @Test
    void field_insertion_order_is_byte_stable() {
        // given
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("service", "a");
        fields.put("kind", "b");
        AgentLogEvent event = new AgentLogEvent(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.INFO,
                AgentEventCodes.BOOT_INSTALLED,
                getClass().getName(),
                "Platform agent logging installed",
                fields,
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
        AgentLogEvent event = new AgentLogEvent(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.WARN,
                AgentEventCodes.SCRUB_FAILURE,
                getClass().getName(),
                "x".repeat(20_000),
                Map.of(),
                null
        );

        // when
        byte[] encoded = encoder.encode(event);

        // then
        assertThat(encoded.length).isLessThanOrEqualTo(AgentLogEvent.MAX_ENCODED_BYTES);
        assertThat(encoded[encoded.length - 1]).isEqualTo((byte) '\n');
        assertThat(new String(encoded, StandardCharsets.UTF_8)).contains("PTA-ENCODE-001");
    }

    @Test
    void argument_values_are_truncated_and_control_chars_are_replaced() {
        // given
        String raw = "a".repeat(AgentLogEvent.MAX_ARGUMENT_CHARS + 16) + "\r\n\u001B";

        // when
        String sanitized = AgentLogTextSanitizer.normalizeBounded(raw, AgentLogEvent.MAX_ARGUMENT_CHARS);

        // then
        assertThat(sanitized).hasSize(AgentLogEvent.MAX_ARGUMENT_CHARS);
        assertThat(sanitized).doesNotContain("\r", "\n", "\u001B");
    }

    @Test
    void forbidden_field_names_are_rejected() {
        // given
        // when
        // then
        assertThat(AgentLogFieldNamePolicy.isRejectedFieldName("secret")).isTrue();
        assertThat(AgentLogFieldNamePolicy.isRejectedFieldName("Authorization")).isTrue();
        assertThat(AgentLogFieldNamePolicy.isRejectedFieldName("message")).isTrue();
    }

    @Test
    void throwable_traversal_is_bounded() {
        // given
        Throwable deepest = new IllegalStateException("secret");
        Throwable current = deepest;
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
                .isEqualTo(AgentLogEvent.MAX_SUPPRESSED);
    }

    @Test
    void oversized_logger_is_replaced_with_fixed_fallback() {
        // given
        String hugeLogger = "x".repeat(20_000);
        AgentLogEvent event = new AgentLogEvent(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.WARN,
                AgentEventCodes.SCRUB_FAILURE,
                hugeLogger,
                "Scrubbing boundary collapsed",
                Map.of(),
                null
        );

        // when
        byte[] encoded = encoder.encode(event);
        String json = new String(encoded, StandardCharsets.UTF_8);

        // then
        assertThat(encoded.length).isLessThanOrEqualTo(AgentLogEvent.MAX_ENCODED_BYTES);
        assertThat(json).contains("\"event_code\":\"PTA-ENCODE-001\"");
        assertThat(json).contains("space.br1440.platform.tracing.otel.javaagent.logging");
        assertThat(json).doesNotContain(hugeLogger);
    }

    @Test
    void unknown_event_code_is_replaced_with_encode_fallback() {
        // given
        AgentLogEvent event = new AgentLogEvent(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.ERROR,
                "FAKE-UNKNOWN-999",
                getClass().getName(),
                "Schema conformance event",
                Map.of(),
                null
        );

        // when
        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

        // then
        assertThat(json).contains("\"event_code\":\"PTA-ENCODE-001\"");
        assertThat(json).doesNotContain("FAKE-UNKNOWN-999");
        assertThat(json).contains("Platform Agent log event could not be encoded safely");
    }

    @Test
    void encoder_escapes_quotes_and_keeps_truncation_deterministic() {
        // given
        AgentLogEvent first = oversized();
        AgentLogEvent second = oversized();

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

    private static AgentLogEvent oversized() {
        return new AgentLogEvent(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.ERROR,
                AgentEventCodes.EXPORT_SAFE_EXPORTER,
                AgentLoggingPolicyConformanceTest.class.getName(),
                "quote\"x".repeat(4_000),
                Map.of(),
                null
        );
    }

    private static AgentLogEvent quoted() {
        return new AgentLogEvent(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.ERROR,
                AgentEventCodes.EXPORT_SAFE_EXPORTER,
                AgentLoggingPolicyConformanceTest.class.getName(),
                "quote\"x",
                Map.of(),
                null
        );
    }
}
