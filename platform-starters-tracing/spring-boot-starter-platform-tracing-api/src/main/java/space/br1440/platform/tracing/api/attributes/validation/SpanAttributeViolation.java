package space.br1440.platform.tracing.api.attributes.validation;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.span.SpanCategory;

/**
 * Описание одного нарушения платформенного контракта атрибутов span'а.
 */
public record SpanAttributeViolation(@Nonnull String ruleId,
                                     @Nonnull SpanCategory category,
                                     @Nonnull String builder,
                                     @Nullable String attributeKey,
                                     @Nonnull String message) {

    public SpanAttributeViolation {
        if (ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }

        if (builder.isBlank()) {
            throw new IllegalArgumentException("builder must not be blank");
        }
    }
}
