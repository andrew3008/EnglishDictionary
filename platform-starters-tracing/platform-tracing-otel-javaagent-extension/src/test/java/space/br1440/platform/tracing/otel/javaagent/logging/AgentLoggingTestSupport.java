package space.br1440.platform.tracing.otel.javaagent.logging;

public final class AgentLoggingTestSupport {

    private AgentLoggingTestSupport() {
    }

    public static void reset() {
        AgentLoggingRuntime.reset();
    }

    static void install(PlatformStderrSink sink) {
        AgentLoggingRuntime.shared().install(sink);
    }
}
