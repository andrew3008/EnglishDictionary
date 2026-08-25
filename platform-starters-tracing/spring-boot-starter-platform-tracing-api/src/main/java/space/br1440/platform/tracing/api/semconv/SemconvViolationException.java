package space.br1440.platform.tracing.api.semconv;

import jakarta.annotation.Nonnull;

import java.io.Serial;

/**
 * Нарушение платформенного semantic-контракта, обнаруженное в режиме {@link SemconvValidationMode#STRICT}.
 * <p>
 * Бросается ТОЛЬКО в режиме {@link SemconvValidationMode#STRICT} (CI/test).
 * В {@link SemconvValidationMode#WARN} и {@link SemconvValidationMode#DISABLED} нарушения не приводят к исключению.
 */
public final class SemconvViolationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** transient: исключение не предназначено для сериализации с полным графом нарушения. */
    private final transient SemconvViolation violation;

    public SemconvViolationException(@Nonnull SemconvViolation violation) {
        super(violation.message());
        this.violation = violation;
    }

    public SemconvViolation violation() {
        return violation;
    }
}
