package space.br1440.platform.tracing.otel.javaagent.logging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLoggingRuntimeTest {

    @AfterEach
    void restore() {
        AgentLoggingRuntime.resetSharedForTests();
    }

    @Test
    void encoder_truncates_to_max_encoded_bytes() {
        // given
        PlatformLogEventEncoder encoder = new PlatformLogEventEncoder();
        String huge = "x".repeat(20_000);
        AgentLogEvent oversized = new AgentLogEvent(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.WARN,
                AgentEventCodes.SCRUB_FAILURE,
                getClass().getName(),
                huge,
                Map.of(),
                null
        );

        // when
        byte[] encoded = encoder.encode(oversized);

        // then
        String json = new String(encoded, StandardCharsets.UTF_8);
        assertThat(encoded.length).isLessThanOrEqualTo(AgentLogEvent.MAX_ENCODED_BYTES);
        assertThat(encoded[encoded.length - 1]).isEqualTo((byte) '\n');
        assertThat(json).contains("\"event_code\":\"PTA-ENCODE-001\"");
        assertThat(json).contains("Platform Agent log event could not be encoded safely");
        assertThat(json).doesNotContain(huge);
    }

    @Test
    void event_rejects_off_level() {
        // given
        // when
        // then
        assertThatThrownBy(() -> new AgentLogEvent(
                Instant.parse("2026-08-16T19:00:00Z"),
                AgentLogLevel.OFF,
                AgentEventCodes.BOOT_INSTALLED,
                getClass().getName(),
                "Platform agent logging installed",
                Map.of(),
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OFF is not a valid event level");
    }

    @Test
    void sink_write_error_is_delivery_failed() {
        // given
        AgentLoggingRuntime.shared().installForTests(new PlatformStderrSink(new FailingOutputStream()));
        AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.INFO);
        PlatformAgentLogger logger = PlatformAgentLogger.get(AgentLoggingRuntimeTest.class);

        // when
        EmitResult result = logger.info(AgentEventCodes.BOOT_INSTALLED, "Platform agent logging installed",
                "kind", "PLATFORM_STDERR_DIRECT");

        // then
        assertThat(result).isEqualTo(EmitResult.DELIVERY_FAILED);
    }

    @Test
    void disabled_event_level_is_filtered_before_sink() {
        // given
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        AgentLoggingRuntime.shared().installForTests(new PlatformStderrSink(buffer));
        AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.ERROR);
        PlatformAgentLogger logger = PlatformAgentLogger.get(AgentLoggingRuntimeTest.class);

        // when
        EmitResult result = logger.info(AgentEventCodes.BOOT_INSTALLED, "Platform agent logging installed",
                "kind", "PLATFORM_STDERR_DIRECT");

        // then
        assertThat(result).isEqualTo(EmitResult.FILTERED_BY_LEVEL);
        assertThat(buffer.size()).isZero();
    }

    @Test
    void sink_ignores_system_err_replacement() {
        // given
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        AgentLoggingRuntime.shared().installForTests(new PlatformStderrSink(buffer));
        AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.INFO);
        PlatformAgentLogger logger = PlatformAgentLogger.get(AgentLoggingRuntimeTest.class);
        PrintStream previous = System.err;
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));

        try {
            // when
            EmitResult result = logger.info(AgentEventCodes.BOOT_INSTALLED, "Platform agent logging installed",
                "kind", "PLATFORM_STDERR_DIRECT");

            // then
            assertThat(result).isEqualTo(EmitResult.EMITTED);
            assertThat(buffer.toString(StandardCharsets.UTF_8)).contains("\"event_code\":\"PTA-BOOT-001\"");
        } finally {
            System.setErr(previous);
        }
    }

    private static final class FailingOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            throw new IOException("sink write failed");
        }
    }
}
