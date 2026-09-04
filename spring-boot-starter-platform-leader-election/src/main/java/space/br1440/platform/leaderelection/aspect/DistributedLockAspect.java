package space.br1440.platform.leaderelection.aspect;

import java.lang.reflect.Method;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import space.br1440.platform.leaderelection.execution.LockingTaskExecutor;
import space.br1440.platform.leaderelection.execution.keepalive.KeepAliveLockFactory;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.processor.LockConfigurationValidationProcessor;
import space.br1440.platform.leaderelection.processor.LockHookProcessor;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.LockConfigurationExtractor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Aspect
public class DistributedLockAspect {

    private final DistributedLockRegistry lockRegistry;
    private final LockingTaskExecutor lockingTaskExecutor;

    public DistributedLockAspect(DistributedLockRegistry lockRegistry,
                                 LockConfigurationExtractor lockConfigurationExtractor,
                                 LockHookProcessor lockHookProcessor,
                                 KeepAliveLockFactory keepAliveLockFactory,
                                 LockConfigurationValidationProcessor validationProcessor) {
        this.lockRegistry = lockRegistry;
        this.lockingTaskExecutor = new LockingTaskExecutor(
                lockConfigurationExtractor,
                lockHookProcessor,
                keepAliveLockFactory,
                validationProcessor
        );
    }

    @Around("@annotation(space.br1440.platform.leaderelection.annotation.locking.DistributedLock)")
    public Object executeWithLock(ProceedingJoinPoint joinPoint) {
        Object target = joinPoint.getTarget();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        log.debug("Intercepted method {}.{} with @DistributedLock", target.getClass().getSimpleName(), method.getName());

        Object[] result = new Object[1];
        Runnable task = () -> {
            try {
                result[0] = joinPoint.proceed();
            } catch (Throwable throwable) {
                throw new RuntimeException("Method execution failed", throwable);
            }
        };
        lockingTaskExecutor.executeWithLock(task, target, method, lockRegistry);

        return result[0];
    }

}
