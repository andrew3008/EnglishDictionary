package space.br1440.platform.tracing.autoconfigure.bootstrap;

import java.util.Objects;

/**
 * Неизменяемый снимок настроек, которые разрешаются ровно один раз до создания
 * обычных Spring-bean'ов и не меняются через refresh.
 */
public record TracingBootstrapSettings(
        boolean tracingEnabled,
        TracingConfigurationAuthority authority,
        PlatformEnvironmentPosture posture) {

    public TracingBootstrapSettings {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(posture, "posture");
    }
}
