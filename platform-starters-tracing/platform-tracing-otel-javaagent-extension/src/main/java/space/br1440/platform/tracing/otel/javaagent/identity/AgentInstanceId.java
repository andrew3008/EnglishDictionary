package space.br1440.platform.tracing.otel.javaagent.identity;

import java.util.Objects;
import java.util.UUID;

public record AgentInstanceId(UUID value) {

    private static final UUID NIL = new UUID(0L, 0L);

    public AgentInstanceId {
        Objects.requireNonNull(value, "value");

        if (NIL.equals(value)) {
            throw new IllegalArgumentException("AgentInstanceId must not be a nil UUID");
        }
    }

    public static AgentInstanceId random() {
        return new AgentInstanceId(UUID.randomUUID());
    }

    public static AgentInstanceId parse(String value) {
        Objects.requireNonNull(value, "agentInstanceId");

        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw notCanonical(exception);
        }

        if (!parsed.toString().equals(value)) {
            throw notCanonical(null);
        }

        return new AgentInstanceId(parsed);
    }

    @Override
    public String toString() {
        return value.toString();
    }

    private static IllegalArgumentException notCanonical(Throwable cause) {
        return new IllegalArgumentException("AgentInstanceId must be a canonical lowercase UUID", cause);
    }
}
