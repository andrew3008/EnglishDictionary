package space.br1440.platform.errorhandling.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает поле типа {@link space.br1440.platform.errorhandling.core.advice.GlobalExceptionHandler}
 * для автоматической инъекции через {@link ErrorHandlingExtension}.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InjectHandler {
}
