package space.br1440.platform.tracing.autoconfigure.agent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import javax.management.MBeanServer;

/**
 * Deterministic {@link MBeanServer} test doubles used to reproduce JMX failure categories without
 * timing-dependent races between threads.
 */
final class FakeMBeanServers {

    private FakeMBeanServers() {
    }

    /**
     * Wraps a real {@link MBeanServer} so that {@code isRegistered} always reports {@code true}
     * regardless of actual registration state, while every other call (including attribute reads)
     * delegates unchanged. Reproduces "endpoint disappeared between the presence check and the
     * read".
     */
    static MBeanServer alwaysReportRegistered(MBeanServer delegate) {
        return proxy(delegate, (proxy, method, args) ->
                "isRegistered".equals(method.getName()) ? Boolean.TRUE : null);
    }

    /**
     * Wraps a real {@link MBeanServer} so that {@code getAttributes} always throws the given
     * exception, while every other call delegates unchanged.
     */
    static MBeanServer throwingOnGetAttributes(MBeanServer delegate, Exception failure) {
        return proxy(delegate, (proxy, method, args) -> {
            if ("getAttributes".equals(method.getName())) {
                throw failure;
            }
            return null;
        });
    }

    @FunctionalInterface
    private interface ShortCircuit {
        Object handle(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static MBeanServer proxy(MBeanServer delegate, ShortCircuit shortCircuit) {
        InvocationHandler handler = (proxy, method, args) -> {
            Object shortCircuited = shortCircuit.handle(proxy, method, args);
            if (shortCircuited != null) {
                return shortCircuited;
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException invocationTargetException) {
                throw invocationTargetException.getCause();
            }
        };
        return (MBeanServer) Proxy.newProxyInstance(
                MBeanServer.class.getClassLoader(), new Class<?>[] {MBeanServer.class}, handler);
    }
}
