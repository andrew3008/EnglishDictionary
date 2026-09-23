package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingRuntime;
import space.br1440.platform.tracing.otel.javaagent.logging.BoundedThrowableRenderer;
import space.br1440.platform.tracing.otel.javaagent.resilience.RateLimitedLogger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DEBUG не является более доверенным приёмником, чем WARN/ERROR.
 */
class ScrubbingFailureLogPrivacyTest {

    private static final String SECRET = "card=4111111111111111";

    private final AgentLogCapture capture = new AgentLogCapture();

    @BeforeEach
    void enableDebug() {
        capture.install();
        capture.applyLevel(AgentLogLevel.DEBUG);
        AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.DEBUG);
    }

    @AfterEach
    void restore() {
        capture.restore();
    }

    @Test
    @DisplayName("Neither ERROR nor DEBUG publishes the exception message, its cause or a rendered stack trace")
    void debugChannelNeverCarriesTheSensitiveInput() {
        // given
        Throwable failure = new IllegalStateException(
                "rule failed on " + SECRET,
                new IllegalArgumentException("nested " + SECRET));

        // when
        ScrubbingFailureLog.error(new RateLimitedLogger(getClass()), "test.rule_failed", failure);

        // then
        List<CapturedLogEvent> events = capture.events();
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(event -> {
            assertThat(event.message()).doesNotContain(SECRET, "rule failed on", "nested ");
            assertThat(event.fields().values().toString())
                    .doesNotContain(SECRET, "rule failed on", "nested ");
            if (event.throwableDigest() != null) {
                assertThat(event.throwableDigest())
                        .doesNotContain(SECRET, "rule failed on", "nested ");
            }
        });

        assertThat(events).anySatisfy(event -> {
            assertThat(event.level()).isEqualTo(AgentLogLevel.ERROR);
            assertThat(event.throwableDigest())
                    .contains("java.lang.IllegalStateException")
                    .contains("java.lang.IllegalArgumentException");
        });
    }

    @Test
    @DisplayName("Detail is skipped entirely when the DEBUG channel is disabled")
    void detailIsNotRenderedWhenDebugIsDisabled() {
        // given
        AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.WARN);

        // when
        ScrubbingFailureLog.warn(new RateLimitedLogger(getClass()),
                "test.rule_failed", new IllegalStateException(SECRET));

        // then
        assertThat(capture.events())
                .noneMatch(event -> event.level() == AgentLogLevel.DEBUG);
    }

    @Test
    @DisplayName("The digest bounds the cause chain and never reads getMessage()")
    void digestIsBoundedAndMessageFree() {
        // given
        Throwable current = new IllegalStateException(SECRET);
        current.setStackTrace(new StackTraceElement[0]);
        for (int i = 0; i < 10; i++) {
            current = new IllegalStateException(SECRET, current);
            current.setStackTrace(new StackTraceElement[0]);
        }

        // when
        String rendered = BoundedThrowableRenderer.render(current);

        // then
        assertThat(rendered).doesNotContain(SECRET);
        assertThat(rendered).contains("cause chain truncated");
        assertThat(rendered.lines().filter(line -> line.contains("caused by"))).hasSize(4);
    }

    @Test
    @DisplayName("A throwable that fails to render is still reported by class name only")
    void hostileThrowableDoesNotBreakTheFailClosedPath() {
        // given
        Throwable hostile = new IllegalStateException(SECRET) {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new UnsupportedOperationException(SECRET);
            }
        };

        // when
        String rendered = BoundedThrowableRenderer.render(hostile);

        // then
        assertThat(rendered).doesNotContain(SECRET);
        assertThat(rendered).contains("detail rendering failed");
    }

    @Test
    @DisplayName("A synthetic frame is rendered from class, method, file and line")
    void syntheticFrameIsRenderedFromStackTraceElementFields() {
        // given
        Throwable failure = new IllegalStateException("boom");
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.attacker." + SECRET, "call", "leak-" + SECRET + ".java", 1)
        });

        // when
        String rendered = BoundedThrowableRenderer.render(failure);

        // then
        assertThat(rendered).contains("com.attacker." + SECRET + ".call(leak-" + SECRET + ".java:1)");
        assertThat(rendered).doesNotContain("boom");
    }

    @Test
    @DisplayName("A frame is rendered even when its class name starts with the platform prefix")
    void platformPrefixFrameIsRendered() {
        // given
        Throwable failure = new IllegalStateException("boom");
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("space.br1440.platform.tracing.Caller", "call", "leak.java", 1)
        });

        // when
        String rendered = BoundedThrowableRenderer.render(failure);

        // then
        assertThat(rendered).contains("space.br1440.platform.tracing.Caller.call(leak.java:1)");
        assertThat(rendered).doesNotContain("boom");
    }

    @Test
    @DisplayName("A module name on a synthetic frame is not part of the digest")
    void moduleNameIsNotRendered() {
        // given
        Throwable failure = new IllegalStateException("boom");
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("module-" + SECRET, "1.0", null,
                        "com.attacker.Caller", "call", "Caller.java", 1)
        });

        // when
        String rendered = BoundedThrowableRenderer.render(failure);

        // then
        assertThat(rendered).contains("com.attacker.Caller.call(Caller.java:1)");
        assertThat(rendered).doesNotContain(SECRET);
        assertThat(rendered).doesNotContain("boom");
    }

    @Test
    @DisplayName("A fatal error raised while reading a hostile stack trace is propagated, not swallowed")
    void fatalFailureFromGetStackTraceIsPropagated() {
        // given
        Throwable hostile = new IllegalStateException(SECRET) {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new OutOfMemoryError(SECRET);
            }
        };

        // when/then
        assertThrows(OutOfMemoryError.class, () -> BoundedThrowableRenderer.render(hostile));
    }
}
