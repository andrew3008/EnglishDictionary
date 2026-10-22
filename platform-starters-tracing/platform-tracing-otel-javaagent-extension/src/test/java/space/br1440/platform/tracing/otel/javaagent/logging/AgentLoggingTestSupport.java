package space.br1440.platform.tracing.otel.javaagent.logging;

import java.io.OutputStream;
import java.util.Objects;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class AgentLoggingTestSupport {

    public static void reset() {
        AgentLoggingRuntime.reset();
    }

    public static AutoCloseable install(OutputStream stream) {
        return install(new PlatformStderrSink(Objects.requireNonNull(stream, "stream")));
    }

    static AgentLoggingRuntime.ScopedInstallation install(PlatformStderrSink sink) {
        return AgentLoggingRuntime.shared().installScoped(sink);
    }
}
