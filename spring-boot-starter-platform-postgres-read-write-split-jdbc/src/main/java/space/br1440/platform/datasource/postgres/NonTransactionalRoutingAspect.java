package space.br1440.platform.datasource.postgres;

import space.br1440.platform.datasource.routing.DataSourceType;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * AOP-аспект для аннотации {@code @UseReadReplica}.
 * <p>
 * Перехватывает нетранзакционные вызовы, помеченные {@code @UseReadReplica},
 * и направляет их в read-пул через {@link DataSourceContextHolder}.
 * Стандартная маршрутизация через {@code @Transactional(readOnly = true)}
 * обрабатывается {@link RoutingDataSource} через {@link TransactionSynchronizationManager}
 * без участия этого аспекта.
 *
 * <h3>Известное ограничение</h3>
 * {@code @UseReadReplica} недопустимо комбинировать с {@code @Transactional} на одном методе.
 * Аспект выполняется с приоритетом {@code HIGHEST_PRECEDENCE} (до транзакционного advice),
 * поэтому tx-guard ({@code isActualTransactionActive()}) не обнаружит соседний {@code @Transactional}.
 * Используйте {@code @Transactional(readOnly = true)}.
 *
 * @see space.br1440.platform.datasource.annotation.UseReadReplica
 */
@Slf4j
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NonTransactionalRoutingAspect {

    @Around("@annotation(space.br1440.platform.datasource.annotation.UseReadReplica)")
    public Object routeForRead(ProceedingJoinPoint pjp) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("@UseReadReplica ignored: active transaction detected at {}. "
                    + "Use @Transactional(readOnly=true) instead.", pjp.getSignature().toShortString());
            return pjp.proceed();
        }

        DataSourceContextHolder.set(DataSourceType.READ);
        try {
            return pjp.proceed();
        } finally {
            DataSourceContextHolder.clear();
        }
    }
}
