package space.br1440.platform.tracing.runtime.propagation;

import java.util.UUID;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import lombok.experimental.UtilityClass;

/**
 * Валидация и генерация correlation id ({@code X-Request-Id}).
 */
@UtilityClass
public final class RequestIdSupport {

    public static final int MAX_LENGTH = 128;

    @Nonnull
    public static String resolve(@Nullable String incoming) {
        String sanitized = sanitizeOrNull(incoming);
        return (sanitized != null) ? sanitized : UUID.randomUUID().toString();
    }

    @Nullable
    public static String sanitizeOrNull(@Nullable String raw) {
        if (raw == null) {
            return null;
        }

        String t = raw.trim();
        if (t.isEmpty() || (t.length() > MAX_LENGTH)) {
            return null;
        }

        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) {
                return null;
            }
        }

        return t;
    }
}
