package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class AgentLoggingRuntimeTest {

    @AfterEach
    void restore() {
        AgentLoggingRuntime.reset();
    }

    @Test
    void encoder_truncates_to_max_encoded_bytes() {
        // given
        PlatformLogEventEncoder encoder = new PlatformLogEventEncoder();
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < AgentLogRecord.MAX_FIELDS; i++) {
            fields.put("f" + i, "x".repeat(AgentLogRecord.MAX_ARGUMENT_CHARS));
        }

        AgentLogRecord oversized = new AgentLogRecord(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.WARN,
                AgentEventCode.SCRUB_FAILURE,
                "SCRUB_BOUNDARY_FAILED",
                getClass().getName(),
                "scrub failed",
                fields,
                null,
                null,
                17L
        );

        // when
        byte[] encoded = encoder.encode(oversized);

        // then
        String json = new String(encoded, StandardCharsets.UTF_8);
        assertThat(encoded.length).isLessThanOrEqualTo(AgentLogRecord.MAX_ENCODED_BYTES);
        assertThat(encoded[encoded.length - 1]).isEqualTo((byte) '\n');
        assertThat(json).contains("\"event_code\":\"PTA-ENCODE-001\"");
        assertThat(json).contains("\"original_event_code\":\"PTA-SCRUB-013\"");
        assertThat(json).contains("\"original_event_name\":\"SCRUB_BOUNDARY_FAILED\"");
        assertThat(json).contains("\"fallback_reason\":\"byte_budget\"");
        assertThat(json).contains("\"suppressed_count\":\"17\"");
        assertThat(json).doesNotContain("x".repeat(AgentLogRecord.MAX_ARGUMENT_CHARS));
    }

    @Test
    void event_rejects_off_level() {
        // given
        // when
        // then
        assertThatThrownBy(() -> new AgentLogRecord(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.OFF,
                AgentEventCode.BOOT_INSTALLED,
                "EXTENSION_INSTALLED",
                getClass().getName(),
                "Platform agent logging installed",
                Map.of(),
                null,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OFF is not a valid event level");
    }

    @Test
    void sink_write_error_is_delivery_failed() {
        // given
        try (AgentLoggingRuntime.ScopedInstallation ignored = AgentLoggingRuntime.shared()
                .installScoped(new PlatformStderrSink(new FailingOutputStream()))) {
            AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.INFO);
            PlatformAgentLogger logger = PlatformAgentLogger.get(AgentLoggingRuntimeTest.class);

            // when
            EmitResult result = logger.emitAdmitted(
                    BootstrapLogEvent.EXTENSION_INSTALLED,
                    fields -> fields.putString(AgentLogField.PROFILE, "PLATFORM_STDERR_DIRECT")
            );

            // then
            assertThat(result).isEqualTo(EmitResult.DELIVERY_FAILED);
        }
    }

    @Test
    void disabled_event_level_is_filtered_before_sink() {
        // given
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (AgentLoggingRuntime.ScopedInstallation ignored = AgentLoggingRuntime.shared()
                .installScoped(new PlatformStderrSink(buffer))) {
            AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.ERROR);
            PlatformAgentLogger logger = PlatformAgentLogger.get(AgentLoggingRuntimeTest.class);

            // when
            logger.log(BootstrapLogEvent.EXTENSION_INSTALLED, fields ->
                    fields.putString(AgentLogField.PROFILE, "PLATFORM_STDERR_DIRECT")
            );

            // then
            assertThat(buffer.size()).isZero();
        }
    }

    @Test
    void sink_ignores_system_err_replacement() {
        // given
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (AgentLoggingRuntime.ScopedInstallation ignored = AgentLoggingRuntime.shared()
                .installScoped(new PlatformStderrSink(buffer))) {
            AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.INFO);
            PlatformAgentLogger logger = PlatformAgentLogger.get(AgentLoggingRuntimeTest.class);
            PrintStream previous = System.err;
            System.setErr(new PrintStream(OutputStream.nullOutputStream()));

            try {
                // when
                EmitResult result = logger.emitAdmitted(
                        BootstrapLogEvent.EXTENSION_INSTALLED,
                        fields -> fields.putString(AgentLogField.PROFILE, "PLATFORM_STDERR_DIRECT")
                );

                // then
                assertThat(result).isEqualTo(EmitResult.EMITTED);
                assertThat(buffer.toString(StandardCharsets.UTF_8)).contains("\"event_code\":\"PTA-BOOT-001\"");
            } finally {
                System.setErr(previous);
            }
        }
    }

    private static final class FailingOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            throw new IOException("sink write failed");
        }
    }
}
