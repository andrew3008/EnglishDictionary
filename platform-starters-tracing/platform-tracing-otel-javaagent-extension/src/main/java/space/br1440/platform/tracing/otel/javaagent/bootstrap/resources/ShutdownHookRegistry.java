package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

public interface ShutdownHookRegistry {

    void register(String threadName, Runnable action);

}
