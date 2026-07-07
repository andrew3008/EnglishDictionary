package space.br1440.platform.tracing.api.span.sanitize;

import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class UrlSanitizer {

    private static final String REDACTED = "REDACTED";
    private static final int MAX_LENGTH = 1000;

    @Nullable
    public static String sanitize(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        String working = url.strip();

        // userinfo: scheme://user:pass@host -> scheme://host
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

        // query: ?k=v&k2=v2 -> ?k=REDACTED&k2=REDACTED
        int q = working.indexOf('?');
        if (q >= 0) {
            String base = working.substring(0, q);
            int fragment = working.indexOf('#', q);
            String query = fragment >= 0 ? working.substring(q + 1, fragment) : working.substring(q + 1);
            String frag = fragment >= 0 ? working.substring(fragment) : "";
            working = base + '?' + redactQuery(query) + frag;
        }

        return (working.length() > MAX_LENGTH) ? working.substring(0, MAX_LENGTH) : working;
    }

    private static String redactQuery(String query) {
        if (query.isEmpty()) {
            return query;
        }

        String[] pairs = query.split("&", -1);
        StringBuilder sb = new StringBuilder(query.length());
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                sb.append('&');
            }

            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                sb.append(pair, 0, eq + 1).append(REDACTED);
            } else {
                sb.append(pair);
            }
        }

        return sb.toString();
    }

    private static int indexOfAny(String s, int from, char... chars) {
        for (int i = from; i < s.length(); i++) {
            for (char c : chars) {
                if (s.charAt(i) == c) {
                    return i;
                }
            }
        }

        return -1;
    }
}
