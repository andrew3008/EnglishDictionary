package space.br1440.platform.tracing.otel.javaagent.control;

import java.util.Objects;
import java.util.UUID;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class RuntimeInstanceIds {

    public static String requireCanonical(String value) {
        Objects.requireNonNull(value, "runtimeInstanceId");

        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("RuntimeInstanceId must be a canonical UUID", exception);
        }

        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("RuntimeInstanceId must be a canonical lowercase UUID");
        }

        return value;
    }
}
