package space.br1440.platform.leaderelection.schedulerlocking;

import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import space.br1440.platform.leaderelection.execution.LockingTaskExecutor;
import space.br1440.platform.leaderelection.execution.keepalive.KeepAliveLockFactory;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.processor.LockConfigurationValidationProcessor;
import space.br1440.platform.leaderelection.processor.LockHookProcessor;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.LockConfigurationExtractor;

import java.lang.reflect.Field;

@Slf4j
public class LockingInterceptor implements MethodInterceptor {

    private final DistributedLockRegistry lockRegistry;
    private final LockConfigurationExtractor lockConfigurationExtractor;
    private final LockingTaskExecutor lockingTaskExecutor;

    public LockingInterceptor(DistributedLockRegistry lockRegistry,
                              LockConfigurationExtractor lockConfigurationExtractor,
                              LockHookProcessor hookProcessor,
                              KeepAliveLockFactory keepAliveLockFactory,
                              LockConfigurationValidationProcessor validationProcessor) {
        this.lockRegistry = lockRegistry;
        this.lockConfigurationExtractor = lockConfigurationExtractor;
        this.lockingTaskExecutor = new LockingTaskExecutor(
                lockConfigurationExtractor,
                hookProcessor,
                keepAliveLockFactory,
                validationProcessor
        );
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Object[] arguments = invocation.getArguments();
        if (arguments.length >= 1) {
            arguments[0] = wrapTask(arguments[0]);
        } else {
            throw new IllegalStateException("Task scheduler method does not have any arguments");
        }

        return invocation.proceed();
    }

    private Object wrapTask(Object firstArgument) throws NoSuchFieldException, IllegalAccessException {
        if (firstArgument instanceof ScheduledMethodRunnable task) {
            return wrapScheduledMethodRunnable(task);
        } else if (firstArgument != null && firstArgument.getClass().getSimpleName().equals("OutcomeTrackingRunnable")) {
            Field runnableField = firstArgument.getClass().getDeclaredField("runnable");
            runnableField.setAccessible(true);

            Object wrappedRunnable = runnableField.get(firstArgument);
            if (wrappedRunnable instanceof ScheduledMethodRunnable task) {
                return wrapScheduledMethodRunnable(task);
            }
        }

        return firstArgument;
    }

    private Object wrapScheduledMethodRunnable(ScheduledMethodRunnable task) {
        if (!requiresLocking(task)) {
            return task;
        }

        return new LockProtectedRunnable(task, lockingTaskExecutor, lockRegistry);
    }

    private boolean requiresLocking(ScheduledMethodRunnable task) {
        return lockConfigurationExtractor.hasLockAnnotation(task.getMethod());
    }

}
