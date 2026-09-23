package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

public final class JvmShutdownHookRegistry implements ShutdownHookRegistry {

    public static final ShutdownHookRegistry INSTANCE = new JvmShutdownHookRegistry();

    private JvmShutdownHookRegistry() {
    }

    @Override
    public Registration register(String threadName, Runnable action) {
        Thread hook = new Thread(action, threadName);
        Runtime.getRuntime().addShutdownHook(hook);
        return () -> {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shutdownAlreadyInProgress) {
                // JVM shutdown уже запущен
            }
        };
    }
}
