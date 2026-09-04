package space.br1440.platform.tracing.otel.javaagent.logging;

import lombok.experimental.UtilityClass;

@UtilityClass
final class AgentLogTextSanitizer {

    static String normalizeBounded(String raw, int maxChars) {
        if (raw == null) {
            return "";
        }

        if ((raw.length() <= maxChars) && isIdentitySafe(raw)) {
            return raw;
        }

        return rewrite(raw, maxChars);
    }

    static boolean isWireUnsafe(String raw) {
        return (raw == null) || !isIdentitySafe(raw);
    }

    private static boolean isIdentitySafe(String raw) {
        int length = raw.length();
        for (int i = 0; i < length; i++) {
            char ch = raw.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if ((i + 1) >= length || !Character.isLowSurrogate(raw.charAt(i + 1))) {
                    return false;
                }

                i++;
                continue;
            }

            if (Character.isLowSurrogate(ch) || requiresReplacement(ch)) {
                return false;
            }
        }

        return true;
    }

    private static String rewrite(String raw, int maxChars) {
        StringBuilder out = new StringBuilder(Math.min(raw.length(), maxChars));
        int written = 0;
        int i = 0;
        int length = raw.length();
        while (i < length && written < maxChars) {
            char ch = raw.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < length && Character.isLowSurrogate(raw.charAt(i + 1))) {
                    if (written + 2 > maxChars) {
                        break;
                    }

                    out.append(ch).append(raw.charAt(i + 1));

                    written += 2;
                    i += 2;
                    continue;
                }

                out.append('\uFFFD');
                written++;
                i++;
                continue;
            }

            if (Character.isLowSurrogate(ch)) {
                out.append('\uFFFD');
                written++;
                i++;
                continue;
            }

            out.append(requiresReplacement(ch) ? '?' : ch);
            written++;
            i++;
        }

        return out.toString();
    }

    private static boolean requiresReplacement(char ch) {
        if (ch <= 0x1F || ch == 0x7F) {
            return true;
        }

        if (ch >= 0x80 && ch <= 0x9F) {
            return true;
        }

        return switch (ch) {
            case '\u061C', '\u200E', '\u200F', '\u2028', '\u2029',
                    '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
                    '\u2066', '\u2067', '\u2068', '\u2069' -> true;
            default -> false;
        };
    }
}
