package space.br1440.platform.tracing.api.attributes.validation;

import java.io.Serial;
import jakarta.annotation.Nonnull;

/**
 * Нарушение платформенного контракта атрибутов, обнаруженное в режиме {@link SpanAttributeValidationLevel#STRICT}.
 * <p>
 * Бросается ТОЛЬКО в режиме {@link SpanAttributeValidationLevel#STRICT} (CI/test).
 * В {@link SpanAttributeValidationLevel#WARN} и {@link SpanAttributeValidationLevel#DISABLED}
 * нарушения не приводят к исключению.
 */
public final class SpanAttributeViolationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** transient: исключение не предназначено для сериализации с полным графом нарушения. */
    private final transient SpanAttributeViolation violation;

    public SpanAttributeViolationException(@Nonnull SpanAttributeViolation violation) {
        super(violation.message());
        this.violation = violation;
    }

    public SpanAttributeViolation violation() {
        return violation;
    }
}
