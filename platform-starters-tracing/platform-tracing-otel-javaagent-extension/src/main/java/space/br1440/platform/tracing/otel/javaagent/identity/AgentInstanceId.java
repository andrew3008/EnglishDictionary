package space.br1440.platform.tracing.otel.javaagent.identity;

import java.util.Objects;
import java.util.UUID;

public final class AgentInstanceId {

    private static final UUID UNBOUND_VALUE = new UUID(0L, 0L);
    private static final AgentInstanceId UNBOUND = new AgentInstanceId(UNBOUND_VALUE);

    private final UUID value;

    private AgentInstanceId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static AgentInstanceId random() {
        return from(UUID.randomUUID());
    }

    public static AgentInstanceId from(UUID value) {
        UUID requiredValue = Objects.requireNonNull(value, "value");
        if (isNil(requiredValue)) {
            throw new IllegalArgumentException("AgentInstanceId must not be a nil UUID");
        }
        return new AgentInstanceId(requiredValue);
    }

    public static AgentInstanceId parse(String value) {
        Objects.requireNonNull(value, "agentInstanceId");

        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("AgentInstanceId must be a canonical UUID", exception);
        }

        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("AgentInstanceId must be a canonical lowercase UUID");
        }

        return from(parsed);
    }

    public static AgentInstanceId unbound() {
        return UNBOUND;
    }

    public boolean isUnbound() {
        return isNil(value);
    }

    public UUID asUuid() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        return other instanceof AgentInstanceId that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toString();
    }

    private static boolean isNil(UUID value) {
        return (value.getMostSignificantBits() == 0L) && (value.getLeastSignificantBits() == 0L);
    }
}
