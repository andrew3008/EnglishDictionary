package space.br1440.platform.tracing.otel.javaagent;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;

/**
 * Clears process-global gate bindings between tests that construct a fresh
 * {@link PlatformAutoConfigurationCustomizer}.
 */
public final class SharedBootstrapBindingsReset {

    private SharedBootstrapBindingsReset() {
    }

    public static void reset() {
        PlatformPropagationGate.resetSharedForTests();
        PlatformLogControl.resetSharedForTests();
    }
}
