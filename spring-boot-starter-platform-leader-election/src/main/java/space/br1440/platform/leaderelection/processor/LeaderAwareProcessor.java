package space.br1440.platform.leaderelection.processor;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.commons.lang3.ClassUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.util.ReflectionUtils;

import space.br1440.platform.leaderelection.annotation.leadership.LeaderAware;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.event.OnLeaderGrantedEvent;
import space.br1440.platform.leaderelection.event.OnLeaderRevokedEvent;
import space.br1440.platform.leaderelection.leaderelection.model.AsyncLeaderInfoProxy;
import space.br1440.platform.leaderelection.leaderelection.model.LeaderInfo;
import space.br1440.platform.leaderelection.leaderelection.model.LeaderRegistry;
import space.br1440.platform.leaderelection.processor.exception.LeaderAwareConfigurationException;
import space.br1440.platform.leaderelection.processor.exception.LeaderAwareRuntimeException;
import space.br1440.platform.leaderelection.util.AnnotationUtils;
import space.br1440.platform.leaderelection.util.LeadershipNameResolver;
import space.br1440.platform.leaderelection.util.ThreadFactoryBuilder;
import space.br1440.platform.leaderelection.util.ThreadUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LeaderAwareProcessor implements BeanPostProcessor, ApplicationListener<ApplicationEvent>, DisposableBean {

    private static final int SCHEDULER_POOL_SIZE = 4;
    private static final long TTL_MILLIS = 30000; // 30 seconds

    private final LeaderRegistry leaderRegistry;
    private final String leadershipName;
    private final Map<String, List<Consumer<Boolean>>> listeners;
    private final Map<Class<?>, List<Field>> fieldCache;
    private final Map<Class<?>, List<Method>> methodCache;
    private final AtomicReference<Boolean> destroyed;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> scheduledTasks;

    public LeaderAwareProcessor(LeaderRegistry leaderRegistry, Environment environment, LeaderElectionProperties properties) {
        this.leaderRegistry = leaderRegistry;
        this.leadershipName = LeadershipNameResolver.resolve(properties, environment);
        this.listeners = new ConcurrentHashMap<>();
        this.fieldCache = new ConcurrentHashMap<>();
        this.methodCache = new ConcurrentHashMap<>();
        this.destroyed = new AtomicReference<>(false);
        this.scheduler = Executors.newScheduledThreadPool(
                SCHEDULER_POOL_SIZE,
                new ThreadFactoryBuilder()
                        .setNameFormat("platform-leader-aware-%d")
                        .setDaemon(true)
                        .build()
        );
        this.scheduledTasks = new ConcurrentHashMap<>();
    }

    @Override
    public Object postProcessBeforeInitialization(@NotNull Object bean, @NotNull String beanName) throws BeansException {
        if (destroyed.get()) {
            log.warn("LeaderAwareProcessor is destroyed, skipping bean: {}", beanName);
            return bean;
        }

        injectFields(bean, beanName);
        injectMethods(bean, beanName);
        return bean;
    }

    private void injectFields(Object bean, String beanName) {
        Class<?> beanClass = bean.getClass();
        List<Field> fields = fieldCache.computeIfAbsent(beanClass, this::getLeaderAwareFields);

        for (Field field : fields) {
            Optional<LeaderAwareMeta> leaderAwareMetaOpt = resolveLeaderAwareMetadata(field);
            if (leaderAwareMetaOpt.isEmpty()) {
                continue;
            }

            if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                boolean isLeader = leaderRegistry.getLeaderInfo(leadershipName).isLeader();
                setFieldSafely(bean, field, isLeader);
                registerListener(leadershipName, flag -> updateField(bean, field, flag));
            } else if (LeaderInfo.class.isAssignableFrom(field.getType())) {
                LeaderAware ann = leaderAwareMetaOpt.get().annotation();
                long ttl = (ann.ttlMillis() > 0) ? ann.ttlMillis() : TTL_MILLIS;

                String proxyKey = leadershipName + "-" + beanName + "-" + field.getName();
                AsyncLeaderInfoProxy proxy = new AsyncLeaderInfoProxy(leadershipName, leaderRegistry, scheduler, ttl);
                setFieldSafely(bean, field, proxy);
                scheduleRefresh(proxy, ttl, proxyKey);
                registerListener(leadershipName, flag -> proxy.refreshLeaderInfoWithRetry());
            } else {
                String message = String.format("Unsupported field type %s for @LeaderAware on %s.%s", field.getType().getName(), beanName, field.getName());
                throw new LeaderAwareConfigurationException(message);
            }
        }
    }

    private List<Field> getLeaderAwareFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        ReflectionUtils.doWithFields(clazz, field -> {
            if (field.getAnnotation(LeaderAware.class) != null) {
                fields.add(field);
            }
        });
        return fields;
    }

    private Optional<LeaderAwareMeta> resolveLeaderAwareMetadata(AccessibleObject member) {
        LeaderAware annotation = member.getAnnotation(LeaderAware.class);
        if (annotation == null) {
            return Optional.empty();
        }

        AnnotationUtils.makeAccessible(member);
        return Optional.of(new LeaderAwareMeta(annotation));
    }

    private void injectMethods(Object bean, String beanName) {
        Class<?> beanClass = bean.getClass();
        List<Method> methods = methodCache.computeIfAbsent(beanClass, this::getLeaderAwareMethods);

        for (Method method : methods) {
            if (isInvalidLeaderAwareMethod(method)) {
                String message = String.format("Unsupported @LeaderAware method signature %s.%s()", beanName, method.getName());
                throw new LeaderAwareConfigurationException(message);
            }

            Optional<LeaderAwareMeta> leaderAwareMetaOpt = resolveLeaderAwareMetadata(method);
            if (leaderAwareMetaOpt.isEmpty()) {
                continue;
            }

            registerListener(leadershipName, flag -> invokeMethod(bean, method, flag));
        }
    }

    private List<Method> getLeaderAwareMethods(Class<?> clazz) {
        List<Method> methods = new ArrayList<>();
        ReflectionUtils.doWithMethods(clazz, method -> {
            if (method.getAnnotation(LeaderAware.class) != null) {
                methods.add(method);
            }
        });
        return methods;
    }

    private boolean isInvalidLeaderAwareMethod(Method method) {
        int params = method.getParameterCount();
        if (params == 0) {
            return false;
        }

        if (params == 1) {
            Class<?> paramType = method.getParameterTypes()[0];
            return paramType != boolean.class;
        }

        return true;
    }

    private void updateField(Object bean, Field field, Boolean flag) {
        setFieldSafely(bean, field, flag);
    }

    private void setFieldSafely(Object bean, Field field, Object value) {
        try {
            Class<?> fieldType = field.getType();
            Class<?> valueType = value.getClass();

            boolean isCompatible = fieldType.isAssignableFrom(valueType) ||
                    isPrimitiveWrapperCompatible(fieldType, valueType);

            if (!isCompatible) {
                String message = String.format(
                        "Type mismatch for field %s on bean %s: expected %s, got %s",
                        field.getName(), bean.getClass().getName(), fieldType.getName(), valueType.getName()
                );
                throw new LeaderAwareConfigurationException(message);
            }
            field.set(bean, value);
        } catch (IllegalAccessException e) {
            String message = String.format("Failed to update field %s on bean %s", field.getName(), bean.getClass().getName());
            throw new LeaderAwareRuntimeException(message, e);
        }
    }

    private boolean isPrimitiveWrapperCompatible(Class<?> type1, Class<?> type2) {
        if (type1.isPrimitive()) {
            Class<?> wrapper = ClassUtils.primitiveToWrapper(type1);
            return wrapper != null && wrapper.equals(type2);
        }

        if (type2.isPrimitive()) {
            Class<?> wrapper = ClassUtils.primitiveToWrapper(type2);
            return wrapper != null && wrapper.equals(type1);
        }

        return false;
    }

    private void invokeMethod(Object bean, Method method, Boolean flag) {
        try {
            if (method.getParameterCount() == 1) {
                method.invoke(bean, flag);
            } else {
                method.invoke(bean);
            }
        } catch (Exception e) {
            String message = String.format("Failed to invoke method %s on bean %s", method.getName(), bean.getClass().getName());
            throw new LeaderAwareRuntimeException(message, e);
        }
    }

    private void scheduleRefresh(AsyncLeaderInfoProxy proxy, long ttl, String taskKey) {
        synchronized (scheduledTasks) {
            if (destroyed.get()) {
                return;
            }

            ScheduledFuture<?> existingTask = scheduledTasks.get(taskKey);
            if (existingTask != null) {
                existingTask.cancel(false);
                log.debug("Cancelled existing scheduled task for key: {}", taskKey);
            }

            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (!destroyed.get()) {
                        proxy.refreshLeaderInfoWithRetry();
                    }
                } catch (Exception ex) {
                    log.error("Error refreshing leader info for leadershipName = {}, taskKey = {}", proxy.getLeadershipName(), taskKey, ex);
                }
            }, ttl, ttl, TimeUnit.MILLISECONDS);

            scheduledTasks.put(taskKey, future);
        }
    }

    private void registerListener(String role, Consumer<Boolean> listener) {
        listeners.computeIfAbsent(role, r -> new CopyOnWriteArrayList<>()).add(listener);
    }

    private void notifyListeners(String role, boolean isLeader) {
        List<Consumer<Boolean>> roleListeners = listeners.get(role);
        if (roleListeners == null || roleListeners.isEmpty()) {
            return;
        }

        for (Consumer<Boolean> listener : roleListeners) {
            try {
                listener.accept(isLeader);
            } catch (Throwable t) {
                log.error("Listener failed for role={}, isLeader={}", role, isLeader, t);
            }
        }
    }

    @Override
    public void onApplicationEvent(@NotNull ApplicationEvent event) {
        if (event instanceof OnLeaderGrantedEvent granted) {
            notifyListeners(granted.getLeadershipName(), true);
        } else if (event instanceof OnLeaderRevokedEvent revoked) {
            notifyListeners(revoked.getLeadershipName(), false);
        }
    }

    @Override
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }

        synchronized (scheduledTasks) {
            for (Map.Entry<String, ScheduledFuture<?>> entry : scheduledTasks.entrySet()) {
                entry.getValue().cancel(false);
            }
        }

        ThreadUtils.shutdownExecutor(scheduler, Duration.ofSeconds(5));
    }

    private record LeaderAwareMeta(LeaderAware annotation) {
    }

}
