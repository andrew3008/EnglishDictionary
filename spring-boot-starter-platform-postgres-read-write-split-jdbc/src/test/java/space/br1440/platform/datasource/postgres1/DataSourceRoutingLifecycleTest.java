package space.br1440.platform.datasource.postgres.internal.proxy.lifecycle;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DataSourceRoutingLifecycle")
class DataSourceRoutingLifecycleTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(DataSourceRoutingLifecycle.class);
        appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        // Защита от шума при параллельном запуске тестов и при переиспользовании инстанса.
        appender.list.clear();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        if (logger != null && appender != null) {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void start_setsRunning() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);

        lifecycle.start();

        assertThat(lifecycle.isRunning()).isTrue();
    }

    @Test
    void stop_clearsRunning() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);

        lifecycle.start();
        lifecycle.stop();

        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void stop_closesReadPoolBeforeWritePool() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);

        lifecycle.start();
        lifecycle.stop();

        InOrder inOrder = inOrder(read, write);
        inOrder.verify(read).close();
        inOrder.verify(write).close();
    }

    @Test
    void stop_nullDataSource_doesNotThrow() {
        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(null, null);

        lifecycle.start();

        assertThatCode(lifecycle::stop).doesNotThrowAnyException();
    }

    @Test
    void stop_alreadyClosed_skipsClose() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        when(write.isClosed()).thenReturn(true);
        when(read.isClosed()).thenReturn(true);
        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);

        lifecycle.start();
        lifecycle.stop();

        verify(write, never()).close();
        verify(read, never()).close();
    }

    @Test
    @DisplayName("getPhase: SmartLifecycle.DEFAULT_PHASE - 2048 (после WebServerGracefulShutdownLifecycle)")
    void getPhase_returnsDefaultPhaseMinus2048() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);

        assertThat(lifecycle.getPhase()).isEqualTo(SmartLifecycle.DEFAULT_PHASE - 2048);
        // Strong-assertion: фаза должна быть строго меньше фазы web-сервера в Spring Boot 3.x.
        assertThat(lifecycle.getPhase()).isLessThan(SmartLifecycle.DEFAULT_PHASE - 1024);
    }

    @Test
    @DisplayName("stop: alreadyClosed логирует DEBUG 'is already closed, skipping' и не зовёт close()")
    void stop_alreadyClosed_logsDebugSkip() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        when(read.isClosed()).thenReturn(true);
        when(write.isClosed()).thenReturn(true);

        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);
        lifecycle.start();
        lifecycle.stop();

        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.DEBUG)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("read pool is already closed"))
                .anyMatch(m -> m.contains("write pool is already closed"));
    }

    @Test
    @DisplayName("stop: успешный close логирует DEBUG 'Closed ... pool' c poolName")
    void stop_success_logsDebugWithPoolName() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        when(write.getPoolName()).thenReturn("HikariPool-write");
        when(read.getPoolName()).thenReturn("HikariPool-read");

        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);
        lifecycle.start();
        lifecycle.stop();

        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.DEBUG)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("Closed read pool (HikariPool-read)"))
                .anyMatch(m -> m.contains("Closed write pool (HikariPool-write)"));
    }

    @Test
    @DisplayName("stop fail-tolerance: исключение в read.close() не мешает закрыть write — log WARN со stack trace")
    void stop_readCloseThrows_continuesToWriteAndLogsWarn() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        when(read.getPoolName()).thenReturn("HikariPool-read");
        when(write.getPoolName()).thenReturn("HikariPool-write");
        doThrow(new IllegalStateException("forced read close failure")).when(read).close();

        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);
        lifecycle.start();

        assertThatCode(lifecycle::stop).doesNotThrowAnyException();

        verify(read).close();
        verify(write).close();
        assertThat(lifecycle.isRunning()).isFalse();

        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("Error closing read pool")
                        && m.contains("HikariPool-read")
                        && m.contains("forced read close failure"));

        // Проверяем, что в WARN-событии передан Throwable (stack trace).
        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .anyMatch(e -> e.getThrowableProxy() != null
                        && e.getThrowableProxy().getClassName().equals(IllegalStateException.class.getName()));
    }

    @Test
    @DisplayName("stop идемпотентен: повторный вызов на закрытых пулах не падает и логирует DEBUG-skip")
    void stop_idempotent_secondCallOnClosedPools() {
        HikariDataSource write = mock(HikariDataSource.class);
        HikariDataSource read = mock(HikariDataSource.class);
        // Первый вызов: пулы открыты.
        when(read.isClosed()).thenReturn(false, true);
        when(write.isClosed()).thenReturn(false, true);

        DataSourceRoutingLifecycle lifecycle = new DataSourceRoutingLifecycle(write, read);
        lifecycle.start();
        lifecycle.stop();
        appender.list.clear();

        assertThatCode(lifecycle::stop).doesNotThrowAnyException();

        verify(read).close();
        verify(write).close();

        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.DEBUG)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("read pool is already closed"))
                .anyMatch(m -> m.contains("write pool is already closed"));
    }
}
