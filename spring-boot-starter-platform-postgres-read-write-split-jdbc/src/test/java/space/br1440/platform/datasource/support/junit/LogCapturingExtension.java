package space.br1440.platform.datasource.support.junit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Parameter;
import java.util.List;

/**
 * JUnit 5 Extension: захватывает Logback события целевых логгеров через {@link ListAppender}
 * и предоставляет их тесту в виде {@link CapturedLogs} (parameter resolver).
 * <p>
 * Применение:
 * <pre>{@code
 * @ExtendWith(LogCapturingExtension.class)
 * @CaptureLogs({"space.br1440.platform.datasource"})
 * class MyTest {
 *     @Test
 *     void shouldLogWarn(CapturedLogs logs) {
 *         // ... when ...
 *         assertThat(logs.events()).anyMatch(e -> e.getLevel() == Level.WARN);
 *     }
 * }
 * }</pre>
 * <p>
 * Каждый тест получает свежий аппендер; setup/teardown не нужны.
 */
public class LogCapturingExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(LogCapturingExtension.class);
    private static final String STORE_KEY = "captured-logs-state";

    @Override
    public void beforeEach(ExtensionContext context) {
        CaptureLogs annotation = context.getRequiredTestClass().getAnnotation(CaptureLogs.class);
        String[] loggerNames = (annotation != null && annotation.value().length > 0)
                ? annotation.value()
                : new String[]{"space.br1440.platform.datasource"};
        Level level = (annotation != null) ? toLogbackLevel(annotation.level()) : Level.DEBUG;

        State state = new State(loggerNames, level);
        state.attach();
        context.getStore(NAMESPACE).put(STORE_KEY, state);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        State state = context.getStore(NAMESPACE).remove(STORE_KEY, State.class);
        if (state != null) {
            state.detach();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == CapturedLogs.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Parameter parameter = parameterContext.getParameter();
        if (parameter.getType() != CapturedLogs.class) {
            throw new ParameterResolutionException("Unsupported parameter: " + parameter);
        }
        State state = extensionContext.getStore(NAMESPACE).get(STORE_KEY, State.class);
        if (state == null) {
            throw new ParameterResolutionException("LogCapturingExtension state is not initialized");
        }
        return state.captured;
    }

    private static Level toLogbackLevel(CaptureLogs.LogLevel level) {
        return switch (level) {
            case TRACE -> Level.TRACE;
            case DEBUG -> Level.DEBUG;
            case INFO -> Level.INFO;
            case WARN -> Level.WARN;
            case ERROR -> Level.ERROR;
        };
    }

    private static final class State {

        private final String[] loggerNames;
        private final Level level;
        private final CapturedAppender appender = new CapturedAppender();
        private final CapturedLogs captured;

        // сохраняем prev уровни для корректного teardown
        private final Level[] previousLevels;

        State(String[] loggerNames, Level level) {
            this.loggerNames = loggerNames;
            this.level = level;
            this.previousLevels = new Level[loggerNames.length];
            this.captured = new CapturedLogs(appender);
        }

        void attach() {
            appender.start();
            for (int i = 0; i < loggerNames.length; i++) {
                Logger logger = (Logger) LoggerFactory.getLogger(loggerNames[i]);
                previousLevels[i] = logger.getLevel();
                logger.setLevel(level);
                logger.addAppender(appender);
            }
        }

        void detach() {
            for (int i = 0; i < loggerNames.length; i++) {
                Logger logger = (Logger) LoggerFactory.getLogger(loggerNames[i]);
                logger.detachAppender(appender);
                logger.setLevel(previousLevels[i]);
            }
            appender.stop();
        }
    }

    /**
     * Thread-safe обёртка над {@link ListAppender}. Метод {@link #copyOfList()} отдаёт
     * иммутабельный снимок — безопасно итерироваться во время продолжающегося логирования.
     */
    private static final class CapturedAppender extends ListAppender<ILoggingEvent> {
        synchronized List<ILoggingEvent> copyOfList() {
            return List.copyOf(this.list);
        }
    }
}
