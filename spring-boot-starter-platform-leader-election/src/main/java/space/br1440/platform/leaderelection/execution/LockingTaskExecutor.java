package space.br1440.platform.leaderelection.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.leaderelection.execution.keepalive.KeepAliveLock;
import space.br1440.platform.leaderelection.execution.keepalive.KeepAliveLockFactory;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.processor.LockConfigurationValidationProcessor;
import space.br1440.platform.leaderelection.processor.LockHookProcessor;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.LockConfiguration;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.LockConfigurationExtractor;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

@Slf4j
@RequiredArgsConstructor
public class LockingTaskExecutor {

    private final LockConfigurationExtractor configurationExtractor;
    private final LockHookProcessor hookProcessor;
    private final KeepAliveLockFactory keepAliveLockFactory;
    private final LockConfigurationValidationProcessor validationProcessor;

    public void executeWithLock(Runnable task, Object target, Method method, DistributedLockRegistry lockRegistry) {
        String leaseRole;
        Duration lockAtMostFor;
        Duration lockAtLeastFor;
        Duration keepAliveLockUntil;
        boolean isDynamic;

        LockConfigurationValidationProcessor.ValidatedLockConfiguration cachedConfig = validationProcessor.getValidatedConfiguration(method);
        if (cachedConfig != null && !cachedConfig.isDynamic()) {
            leaseRole = cachedConfig.leaseRole();
            lockAtMostFor = cachedConfig.lockAtMostFor();
            lockAtLeastFor = cachedConfig.lockAtLeastFor();
            keepAliveLockUntil = cachedConfig.keepAliveLockUntil();
            isDynamic = false;
        } else {
            LockConfiguration lockConfig = configurationExtractor.extractLockConfiguration(method);
            leaseRole = lockConfig.leaseRole();
            lockAtMostFor = lockConfig.lockAtMostFor();
            lockAtLeastFor = lockConfig.lockAtLeastFor();
            keepAliveLockUntil = lockConfig.keepAliveLockUntil();
            isDynamic = true;

            if (lockAtLeastFor != null && lockAtMostFor != null && lockAtLeastFor.compareTo(lockAtMostFor) > 0) {
                log.warn("Invalid dynamic lock configuration for {}.{}: lockAtLeastFor ({}) > lockAtMostFor ({}). " +
                         "This is a runtime-resolved dynamic value. Ignoring lockAtLeastFor to prevent execution failure.",
                         target.getClass().getSimpleName(), method.getName(), lockAtLeastFor, lockAtMostFor);
                lockAtLeastFor = null;
            }
        }

        log.debug("Attempting to acquire lock '{}' for {}.{} (lockAtMostFor={}, lockAtLeastFor={}, keepAliveLockUntil={}, dynamic={})",
                  leaseRole, target.getClass().getSimpleName(), method.getName(), lockAtMostFor, lockAtLeastFor, keepAliveLockUntil, isDynamic);

        boolean useKeepAlive = (keepAliveLockUntil != null) && (keepAliveLockUntil.toMillis() > 0);

        Lock lock;
        KeepAliveLock keepAliveLock = null;
        if (useKeepAlive) {
            keepAliveLock = keepAliveLockFactory.wrap(
                    lockRegistry.obtain(leaseRole),
                    leaseRole,
                    lockAtMostFor,
                    keepAliveLockUntil
            );
            lock = keepAliveLock;
        } else {
            lock = lockRegistry.obtain(leaseRole);
        }

        boolean acquired = false;
        long startTime = System.currentTimeMillis();

        try {
            if (lockAtMostFor != null) {
                acquired = lock.tryLock(lockAtMostFor.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                acquired = lock.tryLock();
            }

            if (!acquired) {
                log.debug("Could not acquire lock '{}' for {}.{}, skipping execution",
                          leaseRole, target.getClass().getSimpleName(), method.getName());
                return;
            }

            log.debug("Lock '{}' acquired for {}.{}", leaseRole, target.getClass().getSimpleName(), method.getName());

            if (useKeepAlive) {
                keepAliveLock.startExtension();
            }

            if (hookProcessor != null) {
                try {
                    hookProcessor.executeOnLockAcquiredHooks(leaseRole);
                } catch (Exception e) {
                    log.error("Error executing OnLockAcquired hooks for leaseRole '{}': {}", leaseRole, e.getMessage(), e);
                }
            }

            task.run();

            if (lockAtLeastFor != null) {
                long executionTime = System.currentTimeMillis() - startTime;
                long minLockTime = lockAtLeastFor.toMillis();
                if (executionTime < minLockTime) {
                    long remainingTime = minLockTime - executionTime;
                    log.debug("Waiting for {}ms to satisfy lockAtLeastFor requirement", remainingTime);

                    try {
                        Thread.sleep(remainingTime);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();

                        String message = String.format(
                                "Min lock time wait interrupted for %s.%s",
                                target.getClass().getSimpleName(), method.getName()
                        );
                        throw new RuntimeException(message, e);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            String message = String.format(
                    "Task execution interrupted for %s.%s",
                    target.getClass().getSimpleName(), method.getName()
            );
            throw new RuntimeException(message, e);
        } catch (Exception e) {
            String message = String.format(
                    "Error executing task %s.%s",
                    target.getClass().getSimpleName(), method.getName()
            );
            throw new RuntimeException(message, e);
        } finally {
            if (acquired) {
                if (useKeepAlive) {
                    keepAliveLock.stopExtension();
                }

                if (hookProcessor != null) {
                    try {
                        hookProcessor.executeOnLockReleasedHooks(leaseRole);
                    } catch (Exception e) {
                        log.error("Error executing OnLockReleased hooks for leaseRole '{}': {}", leaseRole, e.getMessage(), e);
                    }
                }

                try {
                    lock.unlock();
                    log.debug("Lock '{}' released for {}.{}", leaseRole, target.getClass().getSimpleName(), method.getName());
                } catch (Exception e) {
                    log.warn("Error releasing lock '{}' for {}.{}", leaseRole, target.getClass().getSimpleName(), method.getName(), e);
                }
            }
        }
    }

}
