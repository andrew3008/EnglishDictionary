package space.br1440.platform.datasource.support.assertj;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.assertj.core.api.AbstractAssert;

import java.util.List;

/**
 * Fluent AssertJ для списка Logback {@link ILoggingEvent}.
 */
public class LogEventsAssert extends AbstractAssert<LogEventsAssert, List<ILoggingEvent>> {

    public LogEventsAssert(List<ILoggingEvent> events) {
        super(events, LogEventsAssert.class);
    }

    public LogEventsAssert containsMessage(Level level, String fragment) {
        isNotNull();
        boolean found = actual.stream()
                .anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().contains(fragment));
        if (!found) {
            failWithMessage(
                    "Expected log event with level <%s> containing <%s> but none found. Captured: %s",
                    level, fragment, snippet());
        }
        return this;
    }

    public LogEventsAssert doesNotContainMessage(Level level, String fragment) {
        isNotNull();
        boolean found = actual.stream()
                .anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().contains(fragment));
        if (found) {
            failWithMessage(
                    "Expected NO log event with level <%s> containing <%s> but at least one was emitted",
                    level, fragment);
        }
        return this;
    }

    public LogEventsAssert hasNoErrors() {
        isNotNull();
        long errors = actual.stream().filter(e -> e.getLevel() == Level.ERROR).count();
        if (errors > 0) {
            failWithMessage(
                    "Expected NO ERROR-level log events but found <%d>. Snippet: %s",
                    errors, snippet());
        }
        return this;
    }

    public LogEventsAssert hasMessageCount(Level level, int expected) {
        isNotNull();
        long count = actual.stream().filter(e -> e.getLevel() == level).count();
        if (count != expected) {
            failWithMessage(
                    "Expected <%d> log events with level <%s> but found <%d>. Snippet: %s",
                    expected, level, count, snippet());
        }
        return this;
    }

    private String snippet() {
        return actual.stream()
                .limit(20)
                .map(e -> "[" + e.getLevel() + "] " + e.getFormattedMessage())
                .reduce((a, b) -> a + " | " + b)
                .orElse("<empty>");
    }
}
