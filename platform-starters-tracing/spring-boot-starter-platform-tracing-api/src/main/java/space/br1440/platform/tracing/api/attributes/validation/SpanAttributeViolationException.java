package space.br1440.platform.tracing.api.attributes.validation;

import java.io.Serial;
import jakarta.annotation.Nonnull;

/**
 * Нарушение платформенного контракта атрибутов, обнаруженное в режиме {@link SpanAttributeValidationLevel#ERROR}.
 * <p>
 * Бросается ТОЛЬКО в прикладном режиме {@link SpanAttributeValidationLevel#ERROR} (CI/test)
 * до {@code startSpan()}. В {@link SpanAttributeValidationLevel#WARNING} и
 * {@link SpanAttributeValidationLevel#DISABLED} нарушения не приводят к исключению.
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
