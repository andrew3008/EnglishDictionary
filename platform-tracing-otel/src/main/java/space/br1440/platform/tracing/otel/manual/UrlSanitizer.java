package space.br1440.platform.tracing.otel.manual;

import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;

/**
 * Удаляет секреты из URL перед записью значения в span.
 */
@UtilityClass
final class UrlSanitizer {

    private static final String REDACTED = "REDACTED";
    private static final int MAX_LENGTH = 1000;

    @Nullable
    static String sanitize(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        String working = url.strip();

        // Удаляем userinfo: scheme://user:pass@host -> scheme://host.
        int schemeSep = working.indexOf("://");
        if (schemeSep >= 0) {
            int authStart = schemeSep + 3;
            int authEnd = indexOfAny(working, authStart, '/', '?', '#');
            if (authEnd < 0) {
                authEnd = working.length();
            }

            int at = working.lastIndexOf('@', authEnd);
            if (at >= authStart) {
                working = working.substring(0, authStart) + working.substring(at + 1);
            }
        }

        // Сохраняем имена query-параметров, но скрываем их значения.
        int queryStart = working.indexOf('?');
        if (queryStart >= 0) {
            String base = working.substring(0, queryStart);
            int fragmentStart = working.indexOf('#', queryStart);
            String query = fragmentStart >= 0
                    ? working.substring(queryStart + 1, fragmentStart)
                    : working.substring(queryStart + 1);
            String fragment = fragmentStart >= 0 ? working.substring(fragmentStart) : "";
            working = base + '?' + redactQuery(query) + fragment;
        }

        return (working.length() > MAX_LENGTH) ? working.substring(0, MAX_LENGTH) : working;
    }

    private static String redactQuery(String query) {
        if (query.isEmpty()) {
            return query;
        }

        String[] pairs = query.split("&", -1);
        StringBuilder result = new StringBuilder(query.length());
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                result.append('&');
            }

            String pair = pairs[i];
            int separator = pair.indexOf('=');
            if (separator >= 0) {
                result.append(pair, 0, separator + 1).append(REDACTED);
            } else {
                result.append(pair);
            }
        }

        return result.toString();
    }

    private static int indexOfAny(String value, int from, char... candidates) {
        for (int i = from; i < value.length(); i++) {
            for (char candidate : candidates) {
                if (value.charAt(i) == candidate) {
                    return i;
                }
            }
        }

        return -1;
    }
}
