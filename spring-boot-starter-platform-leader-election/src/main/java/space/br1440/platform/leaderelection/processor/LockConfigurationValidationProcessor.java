package space.br1440.platform.leaderelection.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.lang.NonNull;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import space.br1440.platform.leaderelection.annotation.locking.DistributedLock;
import space.br1440.platform.leaderelection.annotation.locking.SchedulerLock;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.LockConfiguration;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.LockConfigurationExtractor;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
public class LockConfigurationValidationProcessor implements BeanPostProcessor, DisposableBean {

    private final LockConfigurationExtractor configurationExtractor;
    private final Map<Method, ValidatedLockConfiguration> validatedConfigCache = new ConcurrentHashMap<>();
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        if (destroyed.get()) {
            return bean;
        }

        Class<?> targetClass = bean.getClass();

        ReflectionUtils.doWithMethods(targetClass, method -> {
            if (configurationExtractor.hasLockAnnotation(method)) {
                validateAndCacheConfiguration(bean, method);
            }
        }, configurationExtractor::hasLockAnnotation);

        return bean;
    }

    private void validateAndCacheConfiguration(Object bean, Method method) {
        try {
            LockConfiguration config = configurationExtractor.extractLockConfiguration(method);

            Duration lockAtMostFor = config.lockAtMostFor();
            Duration lockAtLeastFor = config.lockAtLeastFor();

            if (lockAtLeastFor != null && lockAtMostFor != null) {
                validateLockTimings(bean, method, lockAtLeastFor, lockAtMostFor);
            }

            boolean isDynamic = isDynamic(method);

            ValidatedLockConfiguration validatedConfig = new ValidatedLockConfiguration(
                    config.leaseRole(),
                    lockAtMostFor,
                    lockAtLeastFor,
                    config.keepAliveLockUntil(),
                    isDynamic
            );

            validatedConfigCache.put(method, validatedConfig);

            log.debug("Validated and cached lock configuration for {}.{}: {} (dynamic={})",
                    bean.getClass().getSimpleName(), method.getName(), validatedConfig, isDynamic);

        } catch (RuntimeException e) {
            String errorMessage = String.format(
                    "Failed to validate lock configuration for %s.%s: %s",
                    bean.getClass().getName(),
                    method.getName(),
                    e.getMessage()
            );
            log.error(errorMessage, e);
            throw new IllegalStateException(errorMessage, e);
        }
    }

    private void validateLockTimings(Object bean, Method method, Duration lockAtLeastFor, Duration lockAtMostFor) {
        String className = bean.getClass().getSimpleName();
        String methodName = method.getName();

        if (lockAtLeastFor.compareTo(lockAtMostFor) > 0) {
            String message = String.format(
                    "Invalid lock configuration for %s.%s: " +
                    "lockAtLeastFor (%s) cannot be greater than lockAtMostFor (%s). " +
                    "The task cannot hold the lock for at least %s when the maximum allowed time is %s.",
                    className, methodName,
                    lockAtLeastFor, lockAtMostFor,
                    lockAtLeastFor, lockAtMostFor
            );
            throw new IllegalStateException(message);
        }

        if (lockAtLeastFor.isNegative()) {
            String message = String.format(
                    "Invalid lock configuration for %s.%s: " +
                    "lockAtLeastFor cannot be negative: %s",
                    className, methodName, lockAtLeastFor
            );
            throw new IllegalStateException(message);
        }

        if (lockAtMostFor.isNegative() || lockAtMostFor.isZero()) {
            String message = String.format(
                    "Invalid lock configuration for %s.%s: " +
                    "lockAtMostFor must be positive: %s",
                    className, methodName, lockAtMostFor
            );
            throw new IllegalStateException(message);
        }

        Duration oneDay = Duration.ofDays(1);
        if (lockAtMostFor.compareTo(oneDay) > 0) {
            log.warn("Lock configuration for {}.{} has unusually long lockAtMostFor: {} (more than 1 day). " +
                     "Consider reviewing if this is intentional.",
                     className, methodName, lockAtMostFor);
        }
    }

    private boolean isDynamic(Method method) {
        SchedulerLock schedulerLock = method.getAnnotation(SchedulerLock.class);
        if (schedulerLock != null) {
            return isDynamicExpression(schedulerLock.lockAtMostFor()) ||
                   isDynamicExpression(schedulerLock.lockAtLeastFor()) ||
                   isDynamicExpression(schedulerLock.keepAliveLockUntil());
        }

        DistributedLock distributedLock = method.getAnnotation(DistributedLock.class);
        if (distributedLock != null) {
            return isDynamicExpression(distributedLock.lockAtMostFor()) ||
                   isDynamicExpression(distributedLock.lockAtLeastFor()) ||
                   isDynamicExpression(distributedLock.keepAliveLockUntil());
        }

        return false;
    }

    private boolean isDynamicExpression(String value) {
        return StringUtils.hasText(value) && (value.contains("${") || value.contains("#{"));
    }

    public ValidatedLockConfiguration getValidatedConfiguration(Method method) {
        if (destroyed.get()) {
            return null;
        }

        return validatedConfigCache.get(method);
    }

    @Override
    public void destroy() {
        destroyed.set(true);
        validatedConfigCache.clear();
    }

    public record ValidatedLockConfiguration(
            String leaseRole,
            Duration lockAtMostFor,
            Duration lockAtLeastFor,
            Duration keepAliveLockUntil,
            boolean isDynamic
    ) {
    }

}

