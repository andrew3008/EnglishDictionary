package space.br1440.platform.errorhandling.testing.annotation;

import java.lang.annotation.*;

/**
 * Регистрирует стратегию в тестовом {@code StrategyTestContext}.
 *
 * <p>Поддерживает как {@link space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy},
 * так и {@link space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy}.
 * Extension инстанцирует стратегию через конструктор, совместимый с зависимостями контекста.
 *
 * <p>Можно указать несколько стратегий через {@code @Repeatable}:
 * <pre>{@code
 * @WithStrategy(TimeoutExceptionStrategy.class)
 * @WithStrategy(SslExceptionStrategy.class)
 * class NetworkStrategyTest { ... }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(WithStrategies.class)
public @interface WithStrategy {

    /**
     * Класс стратегии для регистрации в тестовом контексте.
     */
    Class<?> value();

    /**
     * Опциональный приоритет (ORDER). Если не задан — используется значение из стратегии.
     */
    int order() default Integer.MIN_VALUE;
}
