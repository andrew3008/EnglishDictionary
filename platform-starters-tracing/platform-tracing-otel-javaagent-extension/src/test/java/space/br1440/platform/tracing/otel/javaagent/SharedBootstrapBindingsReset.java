package space.br1440.platform.tracing.otel.javaagent;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControlTestSupport;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingTestSupport;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class SharedBootstrapBindingsReset {

    public static void reset() {
        PlatformPropagationGate.resetSharedForTests();
        PlatformLogControlTestSupport.reset();
        AgentLoggingTestSupport.reset();
    }
}
