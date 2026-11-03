package space.br1440.platform.tracing.runtime.scrubbing;

import java.util.Set;
import java.util.regex.Pattern;
import jakarta.annotation.Nullable;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class DiagnosticTextSanitizer {

    public static final int MAX_TEXT_LENGTH = 4_096;
    public static final String REDACTED = "<redacted>";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "secret", "clientsecret", "token", "accesstoken", "refreshtoken",
            "apikey", "sessionid", "privatekey", "ssn", "socialsecuritynumber", "firstname", "lastname",
            "fullname", "givenname", "familyname", "preferredusername", "email", "phone", "passport",
            "cardnumber", "authorization", "proxyauthorization", "cookie", "setcookie"
    );
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (?:[A-Z0-9]++ )*PRIVATE KEY-----"
    );

    @Nullable
    public static String mask(@Nullable String text) {
        if ((text == null) || (text.length() > MAX_TEXT_LENGTH)) {
            return null;
        }

        String masked = UrlCredentialMasker.mask(text);
        if ((masked == null) || (masked.length() > MAX_TEXT_LENGTH)) {
            return null;
        }

        if (SensitiveValueDetector.containsJwt(masked) || SensitiveValueDetector.containsEmail(masked)
                || PRIVATE_KEY.matcher(masked).find() || containsSensitiveLabel(masked)) {
            return REDACTED;
        }

        return masked;
    }

    private static boolean containsSensitiveLabel(String text) {
        for (int cursor = 0; cursor < text.length();) {
            if (!isKeyCharacter(text.charAt(cursor))) {
                cursor++;
                continue;
            }

            int keyStart = cursor;
            StringBuilder key = new StringBuilder();
            do {
                char c = text.charAt(cursor++);
                if ((c != '-') && (c != '_') && (c != '.')) {
                    key.append((c >= 'A') && (c <= 'Z') ? (char) (c + ('a' - 'A')) : c);
                }
            } while ((cursor < text.length()) && isKeyCharacter(text.charAt(cursor)));

            String normalized = key.toString();
            int next = skipWhitespace(text, cursor);
            if ((normalized.equals("bearer") || normalized.equals("basic"))
                    && (next > cursor) && (next < text.length())) {
                return true;
            }

            if (!SENSITIVE_KEYS.contains(normalized)) {
                continue;
            }

            if ((next + 1 < text.length()) && (text.charAt(next) == '\\')
                    && ((text.charAt(next + 1) == '\'') || (text.charAt(next + 1) == '"'))) {
                next++;
            }

            if ((next < text.length()) && ((text.charAt(next) == '\'') || (text.charAt(next) == '"'))) {
                next = skipWhitespace(text, next + 1);
            }

            if ((next == text.length()) || ((text.charAt(next) != '=') && (text.charAt(next) != ':'))) {
                continue;
            }

            int valueStart = skipWhitespace(text, next + 1);
            if ((valueStart < text.length()) && !isCompleteMask(text, keyStart, valueStart)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isCompleteMask(String text, int keyStart, int start) {
        if (!text.startsWith("***", start)) {
            return false;
        }

        int end = start + 3;
        if (skipWhitespace(text, end) == text.length()) {
            return true;
        }

        return ((text.charAt(end) == '&') || (text.charAt(end) == '#')) && isUrlQuery(text, keyStart);
    }

    private static boolean isUrlQuery(String text, int keyStart) {
        int separator = text.lastIndexOf("://", keyStart);
        if (separator < 0) {
            return false;
        }

        boolean query = false;
        for (int index = separator + 3; index < keyStart; index++) {
            char c = text.charAt(index);
            if ((c == ' ') || (c == '\t') || (c == '\r') || (c == '\n') || (c == '#')) {
                return false;
            }
            query |= c == '?';
        }
        return query;
    }

    private static int skipWhitespace(String text, int start) {
        int index = start;
        while ((index < text.length()) && (Character.isWhitespace(text.charAt(index))
                || Character.isSpaceChar(text.charAt(index)))) {
            index++;
        }
        return index;
    }

    private static boolean isKeyCharacter(char c) {
        return ((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z')) || ((c >= '0') && (c <= '9'))
                || (c == '-') || (c == '_') || (c == '.');
    }
}
