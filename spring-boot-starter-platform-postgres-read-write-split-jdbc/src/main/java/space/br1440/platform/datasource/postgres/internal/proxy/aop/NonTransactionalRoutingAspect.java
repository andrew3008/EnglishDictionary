package space.br1440.platform.datasource.postgres.internal.proxy.aop;

import space.br1440.platform.datasource.postgres.api.routing.DataSourceContextHolder;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NonTransactionalRoutingAspect {

    @Around("@annotation(space.br1440.platform.datasource.postgres.api.routing.UseReadReplica)")
    public Object routeForRead(ProceedingJoinPoint joinPoint) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("@UseReadReplica ignored: active transaction detected at {}. "
                    + "Use @Transactional(readOnly=true) instead.", joinPoint.getSignature().toShortString());
            return joinPoint.proceed();
        }

        DataSourceContextHolder.set(DataSourceType.READ);
        try {
            return joinPoint.proceed();
        } finally {
            DataSourceContextHolder.clear();
        }
    }
}
