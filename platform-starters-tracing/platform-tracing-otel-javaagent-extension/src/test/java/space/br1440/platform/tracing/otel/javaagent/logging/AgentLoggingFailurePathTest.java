package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

class AgentLoggingFailurePathTest {

    @Test
    void io_failure_before_write_is_not_retried() {
        // given
        CountingFailingOutputStream stream = new CountingFailingOutputStream();
        PlatformAgentLogger logger = logger(new PlatformLogEventEncoder(), stream);

        // when
        EmitResult result = emit(logger);

        // then
        assertThat(result).isEqualTo(EmitResult.DELIVERY_FAILED);
        assertThat(stream.attempts()).isOne();
    }

    @Test
    void runtime_failure_after_partial_write_is_not_retried_with_fallback() {
        // given
        PartiallyFailingOutputStream stream = new PartiallyFailingOutputStream();
        PlatformAgentLogger logger = logger(new PlatformLogEventEncoder(), stream);

        // when
        EmitResult result = emit(logger);

        // then
        assertThat(result).isEqualTo(EmitResult.DELIVERY_FAILED);
        assertThat(stream.attempts()).isOne();
        assertThat(stream.written()).isNotEmpty().doesNotContain("PTA-ENCODE-001");
    }

    @Test
    void encoder_failure_delivers_one_fixed_fallback() {
        // given
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PlatformLogEventEncoder encoder = new PlatformLogEventEncoder() {
            @Override
            EncodedLogEvent encodeWithOutcome(AgentLogRecord event) {
                throw new IllegalStateException("Encoder failed");
            }
        };
        PlatformAgentLogger logger = logger(encoder, sink);

        // when
        EmitResult result = logger.emitAdmitted(
                BootstrapLogEvent.EXTENSION_INSTALLED,
                fields -> fields.putString(AgentLogField.PROFILE, "caller-secret"),
                17L
        );

        // then
        assertThat(result).isEqualTo(EmitResult.FALLBACK_EMITTED);
        assertThat(sink.toString(StandardCharsets.UTF_8))
                .contains("\"event_code\":\"PTA-ENCODE-001\"")
                .contains("\"fallback_reason\":\"invariant_violation\"")
                .contains("event_name", "ENCODE_FALLBACK")
                .contains("original_event_name", "EXTENSION_INSTALLED")
                .contains("\"suppressed_count\":\"17\"")
                .doesNotContain("caller-secret");
        assertThat(sink.toString(StandardCharsets.UTF_8).lines()).hasSize(1);
    }

    @Test
    void fallback_encoder_failure_does_not_reach_sink() {
        // given
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PlatformLogEventEncoder encoder = new PlatformLogEventEncoder() {
            @Override
            EncodedLogEvent encodeWithOutcome(AgentLogRecord event) {
                throw new IllegalStateException("Encoder failed");
            }

            @Override
            byte[] encodeFallback(java.time.Instant timestamp,
                                  AgentLogLevel level,
                                   AgentEventCode original,
                                   String originalEventName,
                                   FallbackReason reason,
                                   long suppressedCount) {
                throw new IllegalStateException("Fallback encoder failed");
            }
        };
        PlatformAgentLogger logger = logger(encoder, sink);

        // when
        EmitResult result = emit(logger);

        // then
        assertThat(result).isEqualTo(EmitResult.DELIVERY_FAILED);
        assertThat(sink.size()).isZero();
    }

    @ParameterizedTest
    @MethodSource("fatalFailures")
    void fatal_delivery_failure_is_propagated(Error failure) {
        // given
        PlatformAgentLogger logger = logger(new PlatformLogEventEncoder(), new ThrowingOutputStream(failure));

        // when
        // then
        assertThatThrownBy(() -> emit(logger))
                .isSameAs(failure);
    }

    @Test
    void reentrant_sink_write_is_suppressed_and_guard_is_cleared() {
        // given
        ReentrantOutputStream sink = new ReentrantOutputStream();
        PlatformAgentLogger logger = logger(new PlatformLogEventEncoder(), sink);
        sink.logger(logger);

        // when
        EmitResult first = emit(logger);
        EmitResult second = emit(logger);

        // then
        assertThat(first).isEqualTo(EmitResult.EMITTED);
        assertThat(second).isEqualTo(EmitResult.EMITTED);
        assertThat(sink.nestedResult()).isEqualTo(EmitResult.DELIVERY_FAILED);
        assertThat(sink.rendered().lines()).hasSize(2);
    }

    @Test
    void scoped_install_preserves_and_restores_complete_runtime_state() {
        // given
        ByteArrayOutputStream originalSink = new ByteArrayOutputStream();
        ByteArrayOutputStream installedSink = new ByteArrayOutputStream();
        AgentLoggingRuntime runtime = new AgentLoggingRuntime(
                new PlatformLogEventEncoder(), new PlatformStderrSink(originalSink), AgentLogLevel.ERROR);
        PlatformAgentLogger logger = new PlatformAgentLogger(getClass().getName(), runtime);

        // when
        try (AgentLoggingRuntime.ScopedInstallation ignored =
                     runtime.installScoped(new PlatformStderrSink(installedSink))) {
            assertThat(runtime.effectiveLevel()).isEqualTo(AgentLogLevel.ERROR);
            runtime.applyLevel(AgentLogLevel.TRACE);
            logger.log(BootstrapLogEvent.EXTENSION_INSTALLED);
        }
        logger.log(ScrubbingLogEvent.SCRUBBING_BOUNDARY_COLLAPSED);

        // then
        assertThat(runtime.effectiveLevel()).isEqualTo(AgentLogLevel.ERROR);
        assertThat(installedSink.toString(StandardCharsets.UTF_8))
                .contains(AgentEventCode.BOOT_INSTALLED.code())
                .doesNotContain(AgentEventCode.SCRUB_BOUNDARY_COLLAPSE.code());
        assertThat(originalSink.toString(StandardCharsets.UTF_8))
                .contains(AgentEventCode.SCRUB_BOUNDARY_COLLAPSE.code())
                .doesNotContain(AgentEventCode.BOOT_INSTALLED.code());
    }

    @Test
    void nested_scoped_install_fails_fast() {
        // given
        AgentLoggingRuntime runtime = new AgentLoggingRuntime(
                new PlatformLogEventEncoder(), new PlatformStderrSink(new ByteArrayOutputStream()), AgentLogLevel.INFO);

        // when
        try (AgentLoggingRuntime.ScopedInstallation ignored = runtime.installScoped(
                new PlatformStderrSink(new ByteArrayOutputStream())
        )) {
            // then
            assertThatThrownBy(() -> {
                try (AgentLoggingRuntime.ScopedInstallation ignored1 = runtime.installScoped(
                        new PlatformStderrSink(new ByteArrayOutputStream())
                )) {
                    ignored.close();
                }
            })
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Agent logging test sink is already installed");
        }
    }

    @SuppressWarnings("removal")
    static Stream<Error> fatalFailures() {
        return Stream.of(
                new InternalError("Fatal virtual machine failure"),
                new ThreadDeath(),
                new NoClassDefFoundError("Fatal linkage failure")
        );
    }

    private static EmitResult emit(PlatformAgentLogger logger) {
        return logger.emitAdmitted(BootstrapLogEvent.EXTENSION_INSTALLED, fields -> {
        });
    }

    private PlatformAgentLogger logger(PlatformLogEventEncoder encoder, OutputStream sink) {
        AgentLoggingRuntime runtime = new AgentLoggingRuntime(
                encoder,
                new PlatformStderrSink(sink),
                AgentLogLevel.TRACE
        );

        return new PlatformAgentLogger(getClass().getName(), runtime);
    }

    private static final class CountingFailingOutputStream extends OutputStream {

        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void write(int ignored) throws IOException {
            attempts.incrementAndGet();
            throw new IOException("Sink write failed");
        }

        @Override
        public void write(@Nonnull byte[] bytes, int offset, int length) throws IOException {
            attempts.incrementAndGet();
            throw new IOException("Sink write failed");
        }

        int attempts() {
            return attempts.get();
        }
    }

    private static final class PartiallyFailingOutputStream extends OutputStream {

        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void write(int value) {
            written.write(value);
        }

        @Override
        public void write(@Nonnull byte[] bytes, int offset, int length) {
            attempts.incrementAndGet();
            written.write(bytes, offset, Math.min(length, 16));
            throw new IllegalStateException("Sink failed after a partial write");
        }

        int attempts() {
            return attempts.get();
        }

        String written() {
            return written.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class ThrowingOutputStream extends OutputStream {

        private final Error failure;

        private ThrowingOutputStream(Error failure) {
            this.failure = failure;
        }

        @Override
        public void write(int ignored) {
            throw failure;
        }

        @Override
        public void write(@Nonnull byte[] bytes, int offset, int length) {
            throw failure;
        }
    }

    private static final class ReentrantOutputStream extends OutputStream {

        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private PlatformAgentLogger logger;
        private EmitResult nestedResult;
        private boolean recursed;

        void logger(PlatformAgentLogger logger) {
            this.logger = logger;
        }

        @Override
        public void write(int value) {
            delegate.write(value);
        }

        @Override
        public void write(@Nonnull byte[] bytes, int offset, int length) {
            if (!recursed) {
                recursed = true;
                nestedResult = emit(logger);
            }

            delegate.write(bytes, offset, length);
        }

        EmitResult nestedResult() {
            return nestedResult;
        }

        String rendered() {
            return delegate.toString(StandardCharsets.UTF_8);
        }
    }
}
