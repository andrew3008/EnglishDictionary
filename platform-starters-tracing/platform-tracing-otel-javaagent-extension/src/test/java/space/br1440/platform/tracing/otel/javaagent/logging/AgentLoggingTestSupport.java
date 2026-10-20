package space.br1440.platform.tracing.otel.javaagent.logging;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class AgentLoggingTestSupport {

    public static void reset() {
        AgentLoggingRuntime.reset();
    }

    static AgentLoggingRuntime.ScopedInstallation install(PlatformStderrSink sink) {
        return AgentLoggingRuntime.shared().installScoped(sink);
    }
}
