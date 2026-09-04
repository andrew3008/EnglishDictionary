package space.br1440.platform.leaderelection.processor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import space.br1440.platform.leaderelection.annotation.leadership.OnLeaderChange;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.event.OnLeaderGrantedEvent;
import space.br1440.platform.leaderelection.event.OnLeaderRevokedEvent;
import space.br1440.platform.leaderelection.processor.exception.LeaderAwareConfigurationException;
import space.br1440.platform.leaderelection.util.LeadershipNameResolver;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class LeaderOnChangeProcessor implements SmartInitializingSingleton, DisposableBean {

    private final ApplicationContext applicationContext;
    private final String leadershipName;
    private final Map<String, Set<InvocationTarget>> roleMethodCache;
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicBoolean scanned = new AtomicBoolean(false);

    public LeaderOnChangeProcessor(ApplicationContext applicationContext, Environment environment, LeaderElectionProperties properties) {
        this.applicationContext = applicationContext;
        this.leadershipName = LeadershipNameResolver.resolve(properties, environment);
        this.roleMethodCache = new ConcurrentHashMap<>();
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (destroyed.get()) {
            return;
        }

        if (!scanned.compareAndSet(false, true)) {
            return;
        }

        scanAnnotatedMethods();
    }

    private void scanAnnotatedMethods() {
        String[] beanNames = applicationContext.getBeanNamesForType(Object.class);
        for (String beanName : beanNames) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Exception ex) {
                log.debug("Skipping bean '{}' during LeaderOnChange scan: {}", beanName, ex.getMessage());
                continue;
            } catch (Throwable ex) {
                throw new LeaderAwareConfigurationException("Failed to scan bean: " + beanName, ex);
            }

            Class<?> beanClass = bean.getClass();
            ReflectionUtils.doWithMethods(beanClass, method -> {
                OnLeaderChange ann = method.getAnnotation(OnLeaderChange.class);
                if (ann == null) {
                    return;
                }

                if (!isSupportedSignature(method)) {
                    String message = """
                            Unsupported @LeaderOnChange method signature %s.%s() - \
                            method must have 0 parameters or 1 boolean parameter
                            """
                            .formatted(beanClass.getName(), method.getName());
                    throw new LeaderAwareConfigurationException(message);
                }

                ReflectionUtils.makeAccessible(method);
                roleMethodCache
                        .computeIfAbsent(leadershipName, r -> new CopyOnWriteArraySet<>())
                        .add(new InvocationTarget(bean, method, ann.on()));
            });
        }
    }

    @EventListener
    public void handleLeaderGranted(OnLeaderGrantedEvent event) {
        if (destroyed.get()) {
            return;
        }

        String leadershipName = event.getLeadershipName();
        triggerMethods(leadershipName, true);
    }

    @EventListener
    public void handleLeaderRevoked(OnLeaderRevokedEvent event) {
        if (destroyed.get()) {
            return;
        }

        String leadershipName = event.getLeadershipName();
        triggerMethods(leadershipName, false);
    }

    private void triggerMethods(String leadershipName, boolean isLeader) {
        if (destroyed.get()) {
            return;
        }

        Set<InvocationTarget> targets = roleMethodCache.get(leadershipName);
        if (CollectionUtils.isEmpty(targets)) {
            return;
        }

        OnLeaderChange.ChangeEvent current = isLeader
                ? OnLeaderChange.ChangeEvent.BECOME_LEADER
                : OnLeaderChange.ChangeEvent.LOST_LEADERSHIP;

        for (InvocationTarget target : targets) {
            Object bean = target.beanRef.get();
            if (bean == null) {
                continue;
            }

            OnLeaderChange.ChangeEvent configured = target.on;
            if (configured != OnLeaderChange.ChangeEvent.BOTH && configured != current) {
                continue;
            }

            Method method = target.method;
            try {
                if (method.getParameterCount() == 1) {
                    method.invoke(bean, isLeader);
                } else {
                    method.invoke(bean);
                }
            } catch (Exception ex) {
                log.error("Failed to invoke @LeaderOnChange method {}.{} for leadershipName [{}]",
                          method.getDeclaringClass().getName(), method.getName(), leadershipName, ex);
            }
        }
    }

    private boolean isSupportedSignature(Method method) {
        int params = method.getParameterCount();
        if (params == 0) {
            return true;
        }

        if (params == 1) {
            Class<?> p = method.getParameterTypes()[0];
            return (p == boolean.class || p == Boolean.class);
        }

        return false;
    }

    @Override
    public void destroy() {
        destroyed.compareAndSet(false, true);
    }

    private static final class InvocationTarget {
        private final WeakReference<Object> beanRef;
        private final Method method;
        private final OnLeaderChange.ChangeEvent on;

        private InvocationTarget(Object bean, Method method, OnLeaderChange.ChangeEvent on) {
            this.beanRef = new WeakReference<>(bean);
            this.method = method;
            this.on = on;
        }
    }

}
