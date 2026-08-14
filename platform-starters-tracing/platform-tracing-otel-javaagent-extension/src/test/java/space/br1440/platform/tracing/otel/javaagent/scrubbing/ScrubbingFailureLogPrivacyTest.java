package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.resilience.RateLimitedLogger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DEBUG не является более доверенным приёмником, чем WARN/ERROR: он пишется в те же файлы и
 * собирается теми же агрегаторами. Поэтому ни один уровень не имеет права опубликовать
 * сообщение исключения, его причины или stack trace, отрендеренный самим логгером.
 */
class ScrubbingFailureLogPrivacyTest {

    private static final String SECRET = "card=4111111111111111";

    private ListAppender<ILoggingEvent> appender;
    private PlatformLogControl.LogLevel previousLevel;

    @BeforeEach
    void enableDebug() {
        previousLevel = PlatformLogControl.shared().getLevel();
        PlatformLogControl.shared().setLevel(PlatformLogControl.LogLevel.DEBUG);

        appender = new ListAppender<>();
        appender.start();
        logger().addAppender(appender);
        logger().setLevel(Level.ALL);
    }

    @AfterEach
    void restore() {
        logger().detachAppender(appender);
        appender.stop();
        PlatformLogControl.shared().setLevel(previousLevel);
    }

    @Test
    @DisplayName("Neither ERROR nor DEBUG publishes the exception message, its cause or a rendered stack trace")
    void debugChannelNeverCarriesTheSensitiveInput() {
        Throwable failure = new IllegalStateException(
                "rule failed on " + SECRET,
                new IllegalArgumentException("nested " + SECRET));

        ScrubbingFailureLog.error(new RateLimitedLogger(LoggerFactory.getLogger(getClass())),
                "test.rule_failed", failure);

        List<ILoggingEvent> events = appender.list;
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getThrowableProxy())
                    .as("a Throwable attached to the event would be rendered with its message")
                    .isNull();
            assertThat(event.getFormattedMessage()).doesNotContain(SECRET, "rule failed on", "nested ");
        });

        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            // Even a platform-owned frame is never rendered as a raw class name: a hostile
            // Throwable can install a synthetic frame whose class name merely starts with the
            // platform package prefix and carries a secret in the remainder of the string.
            assertThat(event.getFormattedMessage())
                    .as("diagnostics still identify the exception types, but every frame collapses "
                            + "to a fixed placeholder rather than a raw (possibly spoofed) class name")
                    .contains("java.lang.IllegalStateException")
                    .contains("java.lang.IllegalArgumentException")
                    .contains("<platform-frame>")
                    .doesNotContain("at " + getClass().getName());
        });
    }

    @Test
    @DisplayName("Detail is skipped entirely when the DEBUG channel is disabled")
    void detailIsNotRenderedWhenDebugIsDisabled() {
        PlatformLogControl.shared().setLevel(PlatformLogControl.LogLevel.WARN);

        ScrubbingFailureLog.warn(new RateLimitedLogger(LoggerFactory.getLogger(getClass())),
                "test.rule_failed", new IllegalStateException(SECRET));

        assertThat(appender.list).noneSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
    }

    @Test
    @DisplayName("The digest bounds the cause chain and never reads getMessage()")
    void digestIsBoundedAndMessageFree() {
        Throwable deepest = new IllegalStateException(SECRET);
        Throwable current = deepest;
        for (int i = 0; i < 10; i++) {
            current = new IllegalStateException(SECRET, current);
        }

        String rendered = ThrowableDigest.render(current);

        assertThat(rendered).doesNotContain(SECRET);
        assertThat(rendered).contains("cause chain truncated");
        assertThat(rendered.lines().filter(line -> line.contains("caused by"))).hasSize(6);
    }

    @Test
    @DisplayName("A throwable that fails to render is still reported by class name only")
    void hostileThrowableDoesNotBreakTheFailClosedPath() {
        Throwable hostile = new IllegalStateException(SECRET) {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new UnsupportedOperationException(SECRET);
            }
        };

        String rendered = ThrowableDigest.render(hostile);

        assertThat(rendered).doesNotContain(SECRET);
        assertThat(rendered).contains("detail rendering failed");
    }

    @Test
    @DisplayName("A synthetic external stack frame carrying a secret in its method/file/class name is never logged")
    void syntheticStackFrameContainingSecretIsNeverLogged() {
        Throwable failure = new IllegalStateException("boom");
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.attacker." + SECRET, "call", "leak-" + SECRET + ".java", 1)
        });

        String rendered = ThrowableDigest.render(failure);

        assertThat(rendered).doesNotContain(SECRET);
        assertThat(rendered).contains("<external-frame>");
    }

    @Test
    @DisplayName("A synthetic frame whose class name merely starts with the platform prefix never leaks its suffix")
    void platformPrefixSpoofedFrameNeverLeaksSecretSuffix() {
        // Hostile Throwable#setStackTrace(...) can install a frame whose class name starts with the
        // platform package prefix but is not actually platform code: "starts with an allowlisted
        // prefix" must not be treated as "safe to render as-is".
        Throwable failure = new IllegalStateException("boom");
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("space.br1440.platform.tracing." + SECRET, "call", "leak.java", 1)
        });

        String rendered = ThrowableDigest.render(failure);

        assertThat(rendered).doesNotContain(SECRET);
        assertThat(rendered).contains("<platform-frame>");
    }

    @Test
    @DisplayName("A secret placed in the module name of a synthetic frame is never logged")
    void secretInModuleNameIsNeverLogged() {
        Throwable failure = new IllegalStateException("boom");
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("module-" + SECRET, "1.0", null,
                        "com.attacker.Caller", "call", "Caller.java", 1)
        });

        String rendered = ThrowableDigest.render(failure);

        assertThat(rendered).doesNotContain(SECRET);
        assertThat(rendered).contains("<external-frame>");
    }

    @Test
    @DisplayName("A fatal error raised while reading a hostile stack trace is propagated, not swallowed")
    void fatalFailureFromGetStackTraceIsPropagated() {
        Throwable hostile = new IllegalStateException(SECRET) {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new OutOfMemoryError(SECRET);
            }
        };

        assertThrows(OutOfMemoryError.class, () -> ThrowableDigest.render(hostile));
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(ScrubbingFailureLogPrivacyTest.class);
    }
}
