package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

record PolicyApplyAttempt(
        String attemptId,
        Instant observedAt,
        PolicySourceAuthority sourceAuthority,
        PolicyApplyTrigger applyTrigger,
        String sourceRevision) {

    static final int MAX_SOURCE_REVISION_LENGTH = 256;

    PolicyApplyAttempt {
        attemptId = requireCanonicalAttemptId(attemptId);
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(sourceAuthority, "sourceAuthority");
        Objects.requireNonNull(applyTrigger, "applyTrigger");
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        if (sourceRevision.length() > MAX_SOURCE_REVISION_LENGTH) {
            throw new IllegalArgumentException(
                    "sourceRevision must contain at most "
                            + MAX_SOURCE_REVISION_LENGTH
                            + " UTF-16 code units");
        }
    }

    private static String requireCanonicalAttemptId(String value) {
        Objects.requireNonNull(value, "attemptId");
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("attemptId must be a canonical UUID", exception);
        }
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("attemptId must be a canonical lowercase UUID");
        }
        return value;
    }
}
