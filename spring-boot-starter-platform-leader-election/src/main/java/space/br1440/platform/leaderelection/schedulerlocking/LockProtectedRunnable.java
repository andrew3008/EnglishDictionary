package space.br1440.platform.leaderelection.schedulerlocking;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import space.br1440.platform.leaderelection.execution.LockingTaskExecutor;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;

@Slf4j
@AllArgsConstructor
public class LockProtectedRunnable implements Runnable {

    private final ScheduledMethodRunnable originalTask;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final DistributedLockRegistry lockRegistry;

    @Override
    public void run() {
        try {
            log.debug("Executing lock-protected task: {}.{}",
                      originalTask.getTarget().getClass().getSimpleName(),
                      originalTask.getMethod().getName());

            lockingTaskExecutor.executeWithLock(
                    originalTask,
                    originalTask.getTarget(),
                    originalTask.getMethod(),
                    lockRegistry
            );
        } catch (Exception e) {
            String message = String.format(
                    "Error executing lock-protected task: %s.%s",
                    originalTask.getTarget().getClass().getSimpleName(),
                    originalTask.getMethod().getName()
            );
            throw new RuntimeException(message, e);
        }
    }

}
