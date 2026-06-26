package space.br1440.platform.datasource.postgres.api.routing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Маркер для корректного выбора dataSource для нетранзакционных чтений.
 * <p>
 *
 * <h3>Взаимодействие с {@code @Transactional}</h3>
 * Комбинирование {@code @UseReadReplica} и {@code @Transactional} на одном методе
 * <b>запрещено</b> — сочетание неоднозначно, так как оба механизма влияют на выбор соединения.
 * <p>
 * Для read-only транзакций используйте {@code @Transactional(readOnly = true)} — это
 * канонический способ выразить намерение чтения одновременно с транзакционными границами.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UseReadReplica {
}
