package space.br1440.platform.tracing.autoconfigure.agent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import javax.management.MBeanServer;

final class FakeMBeanServers {

    private FakeMBeanServers() {
    }

    static MBeanServer alwaysReportRegistered(MBeanServer delegate) {
        return proxy(delegate, (proxy, method, args) ->
                "isRegistered".equals(method.getName()) ? Boolean.TRUE : null);
    }

    static MBeanServer throwingOnGetMBeanInfo(MBeanServer delegate, Exception failure) {
        return proxy(delegate, (proxy, method, args) -> {
            if ("getMBeanInfo".equals(method.getName())) {
                throw failure;
            }
            return null;
        });
    }

    @FunctionalInterface
    private interface ShortCircuit {
        Object handle(Object proxy, Method method, Object[] args) throws Throwable;
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
                MBeanServer.class.getClassLoader(),
                new Class<?>[]{MBeanServer.class},
                handler);
    }
}
