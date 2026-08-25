package space.br1440.platform.tracing.runtime.propagation;

import java.util.UUID;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.common.util.StringUtils;

import lombok.experimental.UtilityClass;

/**
 * Валидация и генерация correlation id ({@code X-Request-Id}).
 */
@UtilityClass
public final class RequestIdSupport {

    public static final int MAX_LENGTH = CanonicalIdGrammar.MAX_LENGTH;

    @Nonnull
    public static String resolve(@Nullable String incoming) {
        String sanitized = sanitizeOrNull(incoming);
        return (sanitized != null) ? sanitized : UUID.randomUUID().toString();
    }

    @Nullable
    public static String sanitizeOrNull(@Nullable String raw) {
        if (StringUtils.isNullOrBlank(raw)) {
            return null;
        }

        String trimmed = raw.trim();
        return CanonicalIdGrammar.isCanonical(trimmed) ? trimmed : null;
    }
}
