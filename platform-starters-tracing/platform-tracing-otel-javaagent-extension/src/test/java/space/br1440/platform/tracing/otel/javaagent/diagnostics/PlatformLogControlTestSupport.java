package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class PlatformLogControlTestSupport {

    public static void reset() {
        PlatformLogControl.reset();
    }
}
