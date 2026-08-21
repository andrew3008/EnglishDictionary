package space.br1440.platform.tracing.runtime.propagation;

import java.util.List;
import java.util.Objects;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.common.util.StringUtils;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class CorrelationIdSupport {

    public static final int MAX_LENGTH = CanonicalIdGrammar.MAX_LENGTH;

    @Nonnull
    public static String requireCanonical(@Nullable String correlationId) {
        Objects.requireNonNull(correlationId, "correlationId");

        if (!CanonicalIdGrammar.isCanonical(correlationId)) {
            throw new IllegalArgumentException("correlationId must match [A-Za-z0-9_-]{1," + MAX_LENGTH + "}");
        }

        return correlationId;
    }

    @Nullable
    public static String sanitizeOrNull(@Nullable String raw) {
        if (StringUtils.isNullOrBlank(raw)) {
            return null;
        }

        String trimmed = raw.trim();
        return CanonicalIdGrammar.isCanonical(trimmed) ? trimmed : null;
    }

    @Nullable
    public static String resolveInbound(@Nullable List<String> headerValues) {
        if (headerValues == null || headerValues.size() != 1) {
            return null;
        }

        String value = headerValues.get(0);
        return CanonicalIdGrammar.isCanonical(value) ? value : null;
    }
}
