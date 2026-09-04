package space.br1440.platform.leaderelection.processor;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import space.br1440.platform.leaderelection.annotation.leadership.OnLeaderGranted;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.event.OnLeaderGrantedEvent;
import space.br1440.platform.leaderelection.processor.exception.LeaderAwareConfigurationException;
import space.br1440.platform.leaderelection.util.LeadershipNameResolver;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class OnLeaderGrantedProcessor implements ApplicationListener<OnLeaderGrantedEvent>, SmartInitializingSingleton, DisposableBean {

    private final ApplicationContext applicationContext;
    private final String leadershipName;
    private final Map<String, Set<InvocationTarget>> roleMethodCache;
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicBoolean scanned = new AtomicBoolean(false);

    public OnLeaderGrantedProcessor(ApplicationContext applicationContext, Environment environment, LeaderElectionProperties properties) {
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

    @Override
    public void onApplicationEvent(@NotNull OnLeaderGrantedEvent event) {
        if (destroyed.get()) {
            return;
        }

        String leadershipName = event.getLeadershipName();
        Set<InvocationTarget> targets = roleMethodCache.get(leadershipName);
        if (CollectionUtils.isEmpty(targets)) {
            return;
        }

        for (InvocationTarget target : targets) {
            Object bean = target.beanRef.get();
            if (bean == null) {
                continue;
            }

            Method method = target.method;
            try {
                if ((method.getParameterCount() == 1) && (method.getParameterTypes()[0] == OnLeaderGrantedEvent.class)) {
                    method.invoke(bean, event);
                } else {
                    method.invoke(bean);
                }
                log.debug("Executed @OnLeaderGranted method {}.{} for leadershipName {}",
                          method.getDeclaringClass().getName(), method.getName(), leadershipName);
            } catch (Exception e) {
                log.error("Failed to execute @OnLeaderGranted method {}.{}",
                          method.getDeclaringClass().getName(), method.getName(), e);
            }
        }
    }

    private void scanAnnotatedMethods() {
        if (destroyed.get()) {
            return;
        }

        String[] beanNames = applicationContext.getBeanNamesForType(Object.class);
        for (String beanName : beanNames) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Exception ex) {
                log.debug("Skipping bean '{}' during OnLeaderGranted scan: {}", beanName, ex.getMessage());
                continue;
            } catch (Throwable ex) {
                log.error("Critical error accessing bean '{}' during OnLeaderGranted scan", beanName, ex);
                throw new LeaderAwareConfigurationException("Failed to scan bean: " + beanName, ex);
            }

            Class<?> beanClass = bean.getClass();
            ReflectionUtils.doWithMethods(beanClass, method -> {
                OnLeaderGranted ann = method.getAnnotation(OnLeaderGranted.class);
                if (ann == null) {
                    return;
                }

                if (!isSupportedSignature(method)) {
                    String message = """
                            Unsupported @OnLeaderGranted method signature %s.%s() - \
                            method must have 0 parameters or 1 OnLeaderGrantedEvent parameter
                            """
                            .formatted(beanClass.getName(), method.getName());
                    throw new LeaderAwareConfigurationException(message);
                }

                ReflectionUtils.makeAccessible(method);
                roleMethodCache
                        .computeIfAbsent(leadershipName, r -> new CopyOnWriteArraySet<>())
                        .add(new InvocationTarget(bean, method));
            });
        }

        if (log.isDebugEnabled()) {
            roleMethodCache.forEach((r, set) -> {
                String message = "Registered {} @OnLeaderGranted handlers for leadershipName '{}'";
                log.debug(message, set.size(), r);
            });
        }
    }

    private boolean isSupportedSignature(Method method) {
        int params = method.getParameterCount();
        if (params == 0) {
            return true;
        }

        return (params == 1) && (method.getParameterTypes()[0] == OnLeaderGrantedEvent.class);
    }

    @Override
    public void destroy() {
        destroyed.compareAndSet(false, true);
    }

    private static final class InvocationTarget {
        private final java.lang.ref.WeakReference<Object> beanRef;
        private final Method method;

        private InvocationTarget(Object bean, Method method) {
            this.beanRef = new java.lang.ref.WeakReference<>(bean);
            this.method = method;
        }
    }

}
