package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.UUID;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class AgentInstanceIdValidator {

    public static String requireCanonical(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }

        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(fieldName + " must be a canonical lowercase UUID", exception);
        }

        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException(fieldName + " must be a canonical lowercase UUID");
        }

        if (parsed.getMostSignificantBits() == 0L && parsed.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(fieldName + " must not be a nil UUID");
        }

        return value;
    }
}
