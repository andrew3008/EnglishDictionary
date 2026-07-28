package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.Objects;
import java.util.UUID;

final class RuntimeInstanceIds {

    private RuntimeInstanceIds() {
    }

    static String requireCanonical(String value) {
        Objects.requireNonNull(value, "runtimeInstanceId");
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("runtimeInstanceId must be a canonical UUID", exception);
        }
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("runtimeInstanceId must be a canonical lowercase UUID");
        }
        return value;
    }
}
