package space.br1440.platform.tracing.api.propagation;

import lombok.experimental.UtilityClass;

import java.util.UUID;

/**
 * Валидация и генерация correlation id ({@code X-Request-Id}).
 * <p>
 * Реализация {@link #sanitizeOrNull(String)} — zero-allocation на hot path
 * (ручной char-цикл вместо {@code Pattern.matcher()}), что уменьшает нагрузку на CPU при потоке мусорных заголовков.
 */
@UtilityClass
public final class RequestIdSupport {

    /** Максимально допустимая длина correlation id. Превышение трактуется как аномалия -> reject. */
    public static final int MAX_LENGTH = 128;

    public static String resolve(String incoming) {
        String sanitized = sanitizeOrNull(incoming);
        return (sanitized != null) ? sanitized : UUID.randomUUID().toString();
    }

    public static String sanitizeOrNull(String raw) {
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
