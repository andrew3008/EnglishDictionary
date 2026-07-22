package space.br1440.platform.tracing.otel.javaagent.jmx.support;

import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.otel.javaagent.safety.ConfigReloadDiagnostics;

@UtilityClass
public class JmxConfigReloadRecorder {

    public static void record(String domain, boolean applied, long version) {
        ConfigReloadDiagnostics.shared().record(domain, applied, "JMX", version);
    }
}
