package space.br1440.platform.tracing.autoconfigure.agent;

import lombok.experimental.UtilityClass;

import org.springframework.util.ClassUtils;

@UtilityClass
public final class OtelJavaAgentMarkerDetector {

    private static final String AGENT_MARKER_CLASS = "io.opentelemetry.javaagent.OpenTelemetryAgent";

    public static boolean isAgentPresent() {
        return ClassUtils.isPresent(AGENT_MARKER_CLASS, null);
    }
}
