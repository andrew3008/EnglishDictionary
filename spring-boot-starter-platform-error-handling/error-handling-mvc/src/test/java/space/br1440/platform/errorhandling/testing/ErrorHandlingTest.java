package space.br1440.platform.errorhandling.testing;

import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Мета-аннотация для тестов, использующих {@link space.br1440.platform.errorhandling.core.advice.GlobalExceptionHandler}.
 *
 * <p>Подключает {@link ErrorHandlingExtension}, который автоматически создаёт
 * handler с заданными параметрами и инжектирует его в поля, аннотированные {@link InjectHandler}.
 *
 * <p>Аннотация ищется вверх по цепочке {@code getEnclosingClass()},
 * поэтому корректно работает с {@code @Nested}-классами JUnit 5.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ErrorHandlingExtension.class)
public @interface ErrorHandlingTest {

    LoggingStrategy loggingStrategy() default LoggingStrategy.CATEGORY;

    String applicationName() default "test-service";
}
