package space.br1440.platform.errorhandling.testing.annotation;

import java.lang.annotation.*;

/**
 * Контейнер для {@link WithStrategy} (необходим для {@code @Repeatable}).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WithStrategies {

    WithStrategy[] value();
}
