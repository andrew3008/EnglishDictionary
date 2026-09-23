package space.br1440.platform.tracing.otel.javaagent.logging;

/**
 * Сброс process-singleton логгера между тестами.
 */
public final class AgentLoggingTestSupport {

    private AgentLoggingTestSupport() {
    }

    public static void reset() {
        AgentLoggingRuntime.reset();
    }

    public static void install(PlatformStderrSink sink) {
        AgentLoggingRuntime.shared().install(sink);
    }
}
