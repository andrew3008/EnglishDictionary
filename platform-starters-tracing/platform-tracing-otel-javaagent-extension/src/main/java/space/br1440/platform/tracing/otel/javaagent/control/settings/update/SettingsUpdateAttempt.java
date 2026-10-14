package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import java.util.Objects;
import java.util.UUID;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;

public record SettingsUpdateAttempt(String attemptId,
                                    SettingsWriter writer) {

    public SettingsUpdateAttempt {
        attemptId = requireCanonicalAttemptId(attemptId);
        Objects.requireNonNull(writer, "writer");
    }

    public static boolean isCanonicalAttemptId(String value) {
        if (StringUtils.isNullOrEmpty(value)) {
            return false;
        }

        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String requireCanonicalAttemptId(String value) {
        Objects.requireNonNull(value, "attemptId");

        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("AttemptId must be a canonical UUID", exception);
        }

        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("AttemptId must be a canonical lowercase UUID");
        }

        return value;
    }
}
