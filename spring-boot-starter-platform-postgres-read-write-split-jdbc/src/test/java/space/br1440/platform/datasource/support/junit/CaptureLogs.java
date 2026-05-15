package space.br1440.platform.datasource.support.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Конфигурация {@link LogCapturingExtension}: список захватываемых логгеров и минимальный уровень.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CaptureLogs {

    String[] value() default {"space.br1440.platform.datasource"};

    LogLevel level() default LogLevel.DEBUG;

    enum LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }
}
