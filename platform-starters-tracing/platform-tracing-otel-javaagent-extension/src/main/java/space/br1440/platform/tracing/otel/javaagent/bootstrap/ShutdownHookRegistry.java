package space.br1440.platform.tracing.otel.javaagent.bootstrap;

interface ShutdownHookRegistry {

    Registration register(String threadName, Runnable action);

}
