package space.br1440.platform.errorhandling.testing.annotation;

import java.lang.annotation.*;

/**
 * Декларативное построение цепочки причин (cause chain) для теста.
 *
 * <p>Для простых типов с конструктором {@code (String)} или {@code ()} Extension
 * создаёт экземпляры автоматически. Для сложных типов без no-arg конструктора
 * ({@code PSQLException}, {@code MongoCommandException}) используйте параметр
 * {@link #factories()} — имена статических методов в тест-классе:
 *
 * <pre>{@code
 * @WithCauseChain(
 *     exceptions = {PSQLException.class, DataAccessException.class},
 *     factories  = {"createPsqlDuplicateKey", ""}  // "" — пробовать (String) конструктор
 * )
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WithCauseChain {

    /**
     * Классы исключений, образующие цепочку. Первый элемент — top-level,
     * последний — deepest cause.
     */
    Class<? extends Throwable>[] exceptions();

    /**
     * Имена статических фабричных методов в тест-классе для создания экземпляров.
     * Пустая строка {@code ""} означает автоматическое создание через конструктор.
     * Массив должен быть пустым (все — авто) или совпадать по длине с {@link #exceptions()}.
     */
    String[] factories() default {};
}
