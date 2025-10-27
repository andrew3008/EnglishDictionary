package space.br1440.platform.leaderelection.schedulerlocking;

import org.aopalliance.aop.Advice;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.lang.NonNull;
import space.br1440.platform.leaderelection.execution.keepalive.KeepAliveLockFactory;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.processor.LockConfigurationValidationProcessor;
import space.br1440.platform.leaderelection.processor.LockHookProcessor;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.LockConfigurationExtractor;

public class SchedulerLockAdvisor extends AbstractPointcutAdvisor {

    private final Advice advice;
    private final Pointcut pointcut = new TaskSchedulerPointcut();

    public SchedulerLockAdvisor(DistributedLockRegistry lockRegistry,
                                LockConfigurationExtractor lockConfigurationExtractor,
                                LockHookProcessor hookProcessor,
                                KeepAliveLockFactory keepAliveLockFactory,
                                LockConfigurationValidationProcessor validationProcessor) {
        this.advice = new LockingInterceptor(
                lockRegistry,
                lockConfigurationExtractor,
                hookProcessor,
                keepAliveLockFactory,
                validationProcessor
        );
    }

    @Override
    @NonNull
    public Advice getAdvice() {
        return advice;
    }

    @Override
    @NonNull
    public Pointcut getPointcut() {
        return pointcut;
    }

}
