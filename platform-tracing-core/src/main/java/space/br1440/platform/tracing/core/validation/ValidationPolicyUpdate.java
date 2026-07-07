package space.br1440.platform.tracing.core.validation;

import java.time.Instant;

/**
 * Validates and builds the next {@link ValidationSnapshot} for atomic runtime policy updates (PR-8A).
 * Side-effect-free; no domain validation beyond boolean flags (schema v1: {@code enabled}, {@code strict}).
 */
public final class ValidationPolicyUpdate {

    private ValidationPolicyUpdate() {
    }

    public static ValidationSnapshot buildNext(
            ValidationSnapshot previous,
            boolean enabled,
            boolean strict,
            String source) {
        return new ValidationSnapshot(
                enabled,
                strict,
                previous.version() + 1,
                Instant.now(),
                normalizeSource(source));
    }

    public static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "JMX";
        }
        return source.trim();
    }
}
