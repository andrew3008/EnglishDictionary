package space.br1440.platform.tracing.api.semconv;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.span.SpanCategory;

/**
 * Описание одного нарушения платформенного semantic-контракта.
 */
public record SemconvViolation(@Nonnull String ruleId,
                               @Nonnull SpanCategory category,
                               @Nonnull String builder,
                               @Nullable String attributeKey,
                               @Nonnull String message) {

    public SemconvViolation {
        if (ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId не должен быть пустым");
        }

        if (builder.isBlank()) {
            throw new IllegalArgumentException("builder не должен быть пустым");
        }
    }
}
