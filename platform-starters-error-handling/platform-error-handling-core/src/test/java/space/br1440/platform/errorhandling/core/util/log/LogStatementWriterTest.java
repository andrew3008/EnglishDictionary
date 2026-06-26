package space.br1440.platform.errorhandling.core.util.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.errorhandling.extension.LogPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@DisplayName("LogStatementWriter")
class LogStatementWriterTest {

    private static final String METHOD = "GET";
    private static final String URI = "/api/v1/test";
    private static final int STATUS = 500;
    private static final String TYPE = "java.lang.RuntimeException";
    private static final String MSG = "boom";
    private static final RuntimeException EX = new RuntimeException(MSG);

    @Test
    @DisplayName("ERROR — всегда со стектрейсом, withStacktrace игнорируется")
    void error_alwaysWithStacktrace() {
        Logger log = mock(Logger.class);

        LogStatementWriter.write(log, LogPolicy.ERROR, false,
                METHOD, URI, STATUS, TYPE, MSG, EX);

        verify(log).error(LogStatementWriter.LOG_FORMAT, METHOD, URI, STATUS, TYPE, MSG, EX);
        verifyNoMoreInteractions(log);
    }

    @Test
    @DisplayName("ERROR — игнорирует withStacktrace=true (никаких дополнительных вызовов)")
    void error_ignoresWithStacktraceTrue() {
        Logger log = mock(Logger.class);

        LogStatementWriter.write(log, LogPolicy.ERROR, true,
                METHOD, URI, STATUS, TYPE, MSG, EX);

        verify(log).error(LogStatementWriter.LOG_FORMAT, METHOD, URI, STATUS, TYPE, MSG, EX);
        verifyNoMoreInteractions(log);
    }

    @ParameterizedTest
    @EnumSource(value = LogPolicy.class, names = {"DEBUG", "INFO", "WARN"})
    @DisplayName("DEBUG/INFO/WARN с withStacktrace=true — вызов с exception-аргументом")
    void nonError_withStacktrace_passesException(LogPolicy policy) {
        Logger log = mock(Logger.class);

        LogStatementWriter.write(log, policy, true,
                METHOD, URI, STATUS, TYPE, MSG, EX);

        switch (policy) {
            case DEBUG -> verify(log).debug(LogStatementWriter.LOG_FORMAT,
                    METHOD, URI, STATUS, TYPE, MSG, EX);
            case INFO -> verify(log).info(LogStatementWriter.LOG_FORMAT,
                    METHOD, URI, STATUS, TYPE, MSG, EX);
            case WARN -> verify(log).warn(LogStatementWriter.LOG_FORMAT,
                    METHOD, URI, STATUS, TYPE, MSG, EX);
            default -> throw new IllegalStateException("unexpected policy: " + policy);
        }
        verifyNoMoreInteractions(log);
    }

    @ParameterizedTest
    @EnumSource(value = LogPolicy.class, names = {"DEBUG", "INFO", "WARN"})
    @DisplayName("DEBUG/INFO/WARN с withStacktrace=false — вызов без exception-аргумента")
    void nonError_withoutStacktrace_omitsException(LogPolicy policy) {
        Logger log = mock(Logger.class);

        LogStatementWriter.write(log, policy, false,
                METHOD, URI, STATUS, TYPE, MSG, EX);

        switch (policy) {
            case DEBUG -> verify(log).debug(LogStatementWriter.LOG_FORMAT,
                    METHOD, URI, STATUS, TYPE, MSG);
            case INFO -> verify(log).info(LogStatementWriter.LOG_FORMAT,
                    METHOD, URI, STATUS, TYPE, MSG);
            case WARN -> verify(log).warn(LogStatementWriter.LOG_FORMAT,
                    METHOD, URI, STATUS, TYPE, MSG);
            default -> throw new IllegalStateException("unexpected policy: " + policy);
        }
        verifyNoMoreInteractions(log);
    }

    @Test
    @DisplayName("Формат сообщения зафиксирован контрактом")
    void formatIsContract() {
        assertThat(LogStatementWriter.LOG_FORMAT).isEqualTo("[{} {}] {} {}: {}");
    }

    @Test
    @DisplayName("Smoke: реальный SLF4J logger принимает все ветви без исключения")
    void smoke_realLoggerAcceptsAllBranches() {
        Logger real = LoggerFactory.getLogger(LogStatementWriterTest.class);

        for (LogPolicy policy : LogPolicy.values()) {
            LogStatementWriter.write(real, policy, true,
                    METHOD, URI, STATUS, TYPE, MSG, EX);
            LogStatementWriter.write(real, policy, false,
                    METHOD, URI, STATUS, TYPE, MSG, EX);
        }
    }
}
