package space.br1440.platform.tracing.autoconfigure.agent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import javax.management.MBeanServer;

/**
 * Wraps a real {@link MBeanServer} to count {@code getAttribute} versus {@code getAttributes}
 * invocations, proving that the probe performs a single batch call rather than falling back to
 * individual attribute reads.
 */
final class CountingMBeanServer implements InvocationHandler {

    int getAttributeCalls;
    int getAttributesCalls;

    private final MBeanServer delegate;

    CountingMBeanServer(MBeanServer delegate) {
        this.delegate = delegate;
    }

    MBeanServer asProxy() {
        return (MBeanServer) Proxy.newProxyInstance(
                MBeanServer.class.getClassLoader(), new Class<?>[] {MBeanServer.class}, this);
    }

    @Override
    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "getAttribute" -> getAttributeCalls++;
            case "getAttributes" -> getAttributesCalls++;
            default -> {
                // no-op: only attribute-read call counts matter for this test double
            }
        }
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException invocationTargetException) {
            throw invocationTargetException.getCause();
        }
    }
}
