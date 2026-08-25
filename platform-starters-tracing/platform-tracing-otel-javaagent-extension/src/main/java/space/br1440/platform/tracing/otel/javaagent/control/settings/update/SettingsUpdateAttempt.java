package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;

public record SettingsUpdateAttempt(String attemptId,
                                    Instant observedAt,
                                    SettingsSourceAuthority sourceAuthority,
                                    SettingsUpdateTrigger applyTrigger,
                                    String sourceRevision) {

    static final int MAX_SOURCE_REVISION_LENGTH = 256;

    public SettingsUpdateAttempt {
        attemptId = requireCanonicalAttemptId(attemptId);
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(sourceAuthority, "sourceAuthority");
        Objects.requireNonNull(applyTrigger, "applyTrigger");
        Objects.requireNonNull(sourceRevision, "sourceRevision");

        if (sourceRevision.length() > MAX_SOURCE_REVISION_LENGTH) {
            throw new IllegalArgumentException(
                    "SourceRevision must contain at most %d UTF-16 code units"
                            .formatted(MAX_SOURCE_REVISION_LENGTH)
            );
        }
    }

    static SettingsUpdateAttempt forPreApplyDiagnostics(String canonicalAttemptId) {
        return new SettingsUpdateAttempt(
                canonicalAttemptId,
                Instant.now(),
                SettingsSourceAuthority.DEV_ACTUATOR,
                SettingsUpdateTrigger.DEVELOPER_COMMAND,
                ""
        );
    }

    static boolean isCanonicalAttemptId(String value) {
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
