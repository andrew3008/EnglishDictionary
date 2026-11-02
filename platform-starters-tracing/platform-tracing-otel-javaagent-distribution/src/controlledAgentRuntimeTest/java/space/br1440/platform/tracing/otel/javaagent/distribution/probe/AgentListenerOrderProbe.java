package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class AgentListenerOrderProbe {

    public static final String RESULT_PREFIX = "AGENT_LISTENER_ORDER_OK";

    private static final String PLATFORM_LISTENER =
            "space.br1440.platform.tracing.otel.javaagent.bootstrap.ActivationAgentListener";

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("Agent listener order probe does not accept arguments");
        }

        Class<?> initializer = Class.forName("io.opentelemetry.javaagent.bootstrap.AgentInitializer", false, null);
        ClassLoader extensions = (ClassLoader) initializer.getMethod("getExtensionsClassLoader").invoke(null);
        require(extensions != null, "Agent extension class loader is unavailable");
        Class<?> listenerType = Class.forName("io.opentelemetry.javaagent.extension.AgentListener", false, extensions);
        Method order = listenerType.getMethod("order");
        Map<String, Integer> orders = new LinkedHashMap<>();
        for (Object listener : ServiceLoader.load(listenerType, extensions)) {
            String name = listener.getClass().getName();
            Integer previous = orders.putIfAbsent(name, (Integer) order.invoke(listener));
            require(previous == null, "Duplicate agent listener: " + name);
        }

        Integer platformOrder = orders.get(PLATFORM_LISTENER);
        require(platformOrder != null, "Platform activation listener is missing");
        require(orders.size() > 1, "Upstream agent listeners are missing");
        for (Map.Entry<String, Integer> entry : orders.entrySet()) {
            if (!PLATFORM_LISTENER.equals(entry.getKey())) {
                require(entry.getValue() < platformOrder,
                        "Agent listener does not run before platform activation: " + entry);
            }
        }

        System.out.println(RESULT_PREFIX + " listeners=" + orders);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
