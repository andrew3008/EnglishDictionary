package space.br1440.platform.tracing.autoconfigure.bootstrap;

import java.util.Objects;

/**
 * Неизменяемый снимок настроек, которые разрешаются ровно один раз до создания
 * обычных Spring-bean'ов и не меняются через refresh.
 */
public record TracingBootstrapSettings(
        TracingMode mode,
        TracingConfigurationAuthority authority,
        PlatformEnvironmentPosture posture,
        boolean configurationEnabled) {

    public TracingBootstrapSettings {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(posture, "posture");
    }

    public boolean tracingEnabled() {
        return mode != TracingMode.DISABLED && configurationEnabled;
    }
}
