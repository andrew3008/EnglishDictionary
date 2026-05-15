package space.br1440.platform.errorhandling.testing.annotation;

import java.lang.annotation.*;

/**
 * Автоматическая верификация {@code ErrorEntry} в {@code AfterEachCallback}.
 *
 * <p>Если тест уже завершился с исключением ({@code ctx.getExecutionException().isPresent()}),
 * верификация пропускается — оригинальная причина падения не подменяется.
 *
 * <p>Для сложных проверок рекомендуется явное использование
 * {@code ErrorHandlingAssertions.assertThat(entry)} вместо этой аннотации.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExpectErrorEntry {

    /**
     * Ожидаемый HTTP-статус (числовой код, например 404, 503).
     */
    int status();

    /**
     * Ожидаемый errorCode. Пустая строка — не проверять.
     */
    String errorCode() default "";

    /**
     * Ожидаемый reason. Пустая строка — не проверять.
     */
    String reason() default "";
}
