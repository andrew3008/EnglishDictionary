package space.br1440.platform.errorhandling.testing.annotation;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import space.br1440.platform.errorhandling.testing.ErrorHandlingExtension;

import java.lang.annotation.*;

/**
 * Мета-аннотация для тестов обработки ошибок.
 *
 * <p>Комбинирует {@link ErrorHandlingExtension} и {@link MockitoExtension},
 * устраняя boilerplate в тестовых классах стратегий.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(ErrorHandlingExtension.class)
@ExtendWith(MockitoExtension.class)
public @interface ErrorHandlingTest {
}
