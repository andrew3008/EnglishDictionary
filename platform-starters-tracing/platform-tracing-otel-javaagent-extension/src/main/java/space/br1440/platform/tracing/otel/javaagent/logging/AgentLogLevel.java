package space.br1440.platform.tracing.otel.javaagent.logging;

import java.util.Locale;
import java.util.Objects;

/**
 * Уровень Agent-логирования.
 * {@link #OFF} допустим только как настроенный уровень, но не как уровень отдельного события.
 */
public enum AgentLogLevel {

    OFF(-1, false),
    ERROR(0, true),
    WARN(1, true),
    INFO(2, true),
    DEBUG(3, true),
    TRACE(4, true);

    private final int detail;
    private final boolean eventLevel;

    AgentLogLevel(int detail, boolean eventLevel) {
        this.detail = detail;
        this.eventLevel = eventLevel;
    }

    boolean permits(AgentLogLevel eventLevel) {
        Objects.requireNonNull(eventLevel, "eventLevel");

        eventLevel.requireEventLevel();
        return (this != OFF) && (eventLevel.detail <= detail);
    }

    AgentLogLevel requireEventLevel() {
        if (!eventLevel) {
            throw new IllegalArgumentException(name() + " is not a valid event level");
        }

        return this;
    }

    public static AgentLogLevel parseOrDefault(String raw, AgentLogLevel fallback) {
        if (raw == null) {
            return fallback;
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return fallback;
        }

        for (AgentLogLevel level : values()) {
            if (level.name().equals(normalized)) {
                return level;
            }
        }

        return fallback;
    }
}
