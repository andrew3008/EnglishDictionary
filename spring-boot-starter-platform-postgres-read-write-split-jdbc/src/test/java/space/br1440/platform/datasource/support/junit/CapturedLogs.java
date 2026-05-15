package space.br1440.platform.datasource.support.junit;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;

/**
 * Контейнер для захваченных Logback-событий, инжектируется в тесты {@link LogCapturingExtension}.
 */
public final class CapturedLogs {

    private final ListAppender<ILoggingEvent> appender;

    CapturedLogs(ListAppender<ILoggingEvent> appender) {
        this.appender = appender;
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    public void clear() {
        appender.list.clear();
    }
}
