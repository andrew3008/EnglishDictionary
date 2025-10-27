package space.br1440.platform.leaderelection.processor;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.ReflectionUtils;
import space.br1440.platform.leaderelection.annotation.locking.OnLockAcquired;
import space.br1440.platform.leaderelection.annotation.locking.OnLockReleased;
import space.br1440.platform.leaderelection.util.ThreadUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
public class LockHookProcessor implements BeanPostProcessor, DisposableBean {

    private static final String HOOK_THREAD_PREFIX = "platform-lock-hook-";
    private static final int DEFAULT_CORE_POOL_SIZE = 2;
    private static final int DEFAULT_MAX_POOL_SIZE = 10;
    private static final int DEFAULT_QUEUE_CAPACITY = 100;
    private static final long DEFAULT_HOOK_TIMEOUT_MS = 30_000L; // 30 seconds

    private final TaskExecutor asyncTaskExecutor;
    private final Map<String, List<HookMethod>> onLockAcquiredHooks = new ConcurrentHashMap<>();
    private final Map<String, List<HookMethod>> onLockReleasedHooks = new ConcurrentHashMap<>();

    public LockHookProcessor() {
        this.asyncTaskExecutor = createAsyncTaskExecutor();
    }

    @Override
    public Object postProcessAfterInitialization(@NotNull Object bean, @NotNull String beanName) throws BeansException {
        ReflectionUtils.doWithMethods(bean.getClass(), method -> {
            OnLockAcquired onAcquired = AnnotationUtils.findAnnotation(method, OnLockAcquired.class);
            if (onAcquired != null) {
                registerHook(
                        onLockAcquiredHooks,
                        onAcquired.lockName(),
                        new HookMethod(bean, method, onAcquired.order(), onAcquired.async())
                );
            }

            OnLockReleased onReleased = AnnotationUtils.findAnnotation(method, OnLockReleased.class);
            if (onReleased != null) {
                registerHook(
                        onLockReleasedHooks,
                        onReleased.lockName(),
                        new HookMethod(bean, method, onReleased.order(), onReleased.async())
                );
            }
        });

        return bean;
    }

    public void executeOnLockAcquiredHooks(String leaseRole) {
        executeHooks(onLockAcquiredHooks.get(leaseRole), "OnLockAcquired", leaseRole);
    }

    public void executeOnLockReleasedHooks(String leaseRole) {
        executeHooks(onLockReleasedHooks.get(leaseRole), "OnLockReleased", leaseRole);
    }

    @Override
    public void destroy() {
        if (asyncTaskExecutor instanceof ThreadPoolTaskExecutor executor) {
            ThreadUtils.shutdownExecutor(executor.getThreadPoolExecutor(), Duration.ofSeconds(30));
        }
    }

    private TaskExecutor createAsyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(DEFAULT_CORE_POOL_SIZE);
        executor.setMaxPoolSize(DEFAULT_MAX_POOL_SIZE);
        executor.setQueueCapacity(DEFAULT_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(HOOK_THREAD_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    private void executeHooks(List<HookMethod> hooks, String hookType, String leaseRole) {
        if (hooks == null || hooks.isEmpty()) {
            return;
        }

        log.debug("Executing {} hooks for leaseRole: {}", hookType, leaseRole);

        List<HookMethod> syncHooks = new ArrayList<>();
        List<HookMethod> asyncHooks = new ArrayList<>();
        for (HookMethod hook : hooks) {
            if (hook.async) {
                asyncHooks.add(hook);
            } else {
                syncHooks.add(hook);
            }
        }

        syncHooks.sort(Comparator.comparingInt(HookMethod::order));
        asyncHooks.sort(Comparator.comparingInt(HookMethod::order));

        for (HookMethod hook : syncHooks) {
            try {
                executeHookSync(hook, hookType, leaseRole);
            } catch (Exception e) {
                log.error("Failed to execute synchronous {} hook for leaseRole: {}, method: {}",
                        hookType, leaseRole, hook.method.getName(), e);
            }
        }

        List<CompletableFuture<Void>> asyncFutures = new ArrayList<>();
        for (HookMethod hook : asyncHooks) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    executeHookSync(hook, hookType, leaseRole);
                } catch (Exception e) {
                    log.error("Failed to execute asynchronous {} hook for leaseRole: {}, method: {}",
                            hookType, leaseRole, hook.method.getName(), e);
                }
            }, asyncTaskExecutor);

            asyncFutures.add(future);
        }

        if ("OnLockReleased".equals(hookType) && !asyncFutures.isEmpty()) {
            try {
                CompletableFuture<Void> allAsyncHooks = CompletableFuture.allOf(
                        asyncFutures.toArray(new CompletableFuture[0])
                );
                allAsyncHooks.get(DEFAULT_HOOK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                log.debug("All asynchronous {} hooks completed for leaseRole: {}", hookType, leaseRole);
            } catch (Exception e) {
                log.warn("Some asynchronous {} hooks did not complete within timeout for leaseRole: {}",
                        hookType, leaseRole, e);
            }
        }
    }

    private void executeHookSync(HookMethod hook, String hookType, String leaseRole) {
        long startTime = System.currentTimeMillis();
        try {
            log.debug("Executing {} hook: {} for leaseRole: {}", hookType, hook.method.getName(), leaseRole);

            ReflectionUtils.makeAccessible(hook.method);
            hook.method.invoke(hook.bean);

            long executionTime = System.currentTimeMillis() - startTime;
            log.debug("Successfully executed {} hook: {} for leaseRole: {} in {}ms",
                    hookType, hook.method.getName(), leaseRole, executionTime);

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Failed to execute {} hook: {} for leaseRole: {} after {}ms",
                    hookType, hook.method.getName(), leaseRole, executionTime, e);
            throw new RuntimeException("Hook execution failed", e);
        }
    }

    private void registerHook(Map<String, List<HookMethod>> hookMap, String leaseRole, HookMethod hookMethod) {
        hookMap.computeIfAbsent(leaseRole, k -> new ArrayList<>()).add(hookMethod);

        String message = "Registered hook: {} for leaseRole: {}, order: {}, async: {}";
        log.debug(message, hookMethod.method.getName(), leaseRole, hookMethod.order, hookMethod.async);
    }

    private record HookMethod(Object bean, Method method, int order, boolean async) {
    }

}
