package space.br1440.platform.leaderelection.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import space.br1440.platform.leaderelection.annotation.leadership.OnLeaderChange;
import space.br1440.platform.leaderelection.processor.executor.LeaderChangeTaskExecutor;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Aspect
@RequiredArgsConstructor
public class LeaderOnChangeAspect {

    private static final long ASYNC_TIMEOUT_MS = 30000; // 30 seconds

    private final LeaderChangeTaskExecutor taskExecutor;
    private final Map<Method, Meta> methodMetaCache = new ConcurrentHashMap<>();

    @Around("@annotation(space.br1440.platform.leaderelection.annotation.leadership.OnLeaderChange)")
    public Object aroundLeaderOnChange(ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Meta meta = methodMetaCache.computeIfAbsent(method, this::buildMeta);
        String methodSignature = getMethodSignature(method);
        Callable<Object> coreCall = () -> {
            try {
                return joinPoint.proceed();
            } catch (Exception | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException("Unexpected error in LeaderOnChange execution", t);
            }
        };

        if (meta.async) {
            return executeAsync(coreCall, methodSignature);
        } else {
            return executeSync(coreCall, methodSignature);
        }
    }

    private CompletableFuture<Object> executeAsync(Callable<Object> coreCall, String methodSignature) {
        long startTime = System.currentTimeMillis();

        return CompletableFuture
                .supplyAsync(() -> callUnchecked(coreCall), taskExecutor.getLeaderChangeAsyncExecutor())
                .orTimeout(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    long duration = System.currentTimeMillis() - startTime;
                    handleAsyncException(ex, methodSignature, duration);
                    return null;
                });
    }

    private Object executeSync(Callable<Object> coreCall, String methodSignature) {
        long startTime = System.currentTimeMillis();

        try {
            return coreCall.call();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            handleSyncException(e, methodSignature, duration);
            return null;
        }
    }

    private void handleAsyncException(Throwable ex, String methodSignature, long duration) {
        if (ex instanceof TimeoutException) {
            log.error("LeaderOnChange async execution timed out for method [{}] after {}ms", methodSignature, duration);
        } else if (ex instanceof RuntimeException && ex.getCause() instanceof InterruptedException) {
            log.warn("LeaderOnChange async execution was interrupted for method [{}] after {}ms", methodSignature, duration);
            Thread.currentThread().interrupt();
        } else {
            log.error("LeaderOnChange async execution failed for method [{}] after {}ms", methodSignature, duration, ex);
        }
    }

    private void handleSyncException(Exception e, String methodSignature, long duration) {
        log.error("LeaderOnChange sync execution failed for method [{}] after {}ms", methodSignature, duration, e);
    }

    private String getMethodSignature(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    private Object callUnchecked(Callable<Object> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw new RuntimeException("Execution failed", e);
        }
    }

    private Meta buildMeta(Method method) {
        OnLeaderChange annotation = method.getAnnotation(OnLeaderChange.class);

        Meta meta = new Meta();
        meta.async = annotation != null && annotation.async();
        return meta;
    }

    private static final class Meta {
        private boolean async;
    }

    @FunctionalInterface
    private interface Callable<T> {
        T call() throws Exception;
    }

}
