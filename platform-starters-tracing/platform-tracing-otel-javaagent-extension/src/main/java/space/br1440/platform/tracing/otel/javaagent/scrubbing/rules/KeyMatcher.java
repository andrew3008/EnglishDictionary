package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import lombok.experimental.UtilityClass;

import java.util.Locale;

import space.br1440.platform.common.util.StringUtils;

@UtilityClass
final class KeyMatcher {

    static String normalize(String key) {
        if (StringUtils.isNullOrEmpty(key)) {
            return "";
        }

        String lowered = key.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lowered.length());
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            if (c == '-' || c == '_' || c == '.' || c == ' ') {
                continue;
            }

            sb.append(c);
        }

        return sb.toString();
    }

    static boolean containsAny(String normalizedKey, String... normalizedTokens) {
        if (normalizedKey.isEmpty()) {
            return false;
        }

        for (String token : normalizedTokens) {
            if (!token.isEmpty() && normalizedKey.contains(token)) {
                return true;
            }
        }

        return false;
    }
}
