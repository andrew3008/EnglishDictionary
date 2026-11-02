package space.br1440.platform.tracing.runtime.scrubbing;

import java.util.Objects;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import lombok.experimental.UtilityClass;

/**
 * Маскирует credentials в URL внутри произвольного текста без декодирования URL: userinfo, значения
 * {@code key=value} в query и fragment целиком.
 */
@UtilityClass
public final class UrlCredentialMasker {

    private static final int MAX_SCAN_LENGTH = 4_096;
    private static final int MAX_PORT_DIGITS = 5;
    private static final String SCHEME_SEPARATOR = "://";
    private static final String MASK = "***";

    @Nullable
    public static String mask(@Nonnull String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_SCAN_LENGTH) {
            return null;
        }

        MaskedText result = new MaskedText(text);
        int cursor = 0;
        int separator = text.indexOf(SCHEME_SEPARATOR);
        while (separator >= 0) {
            int schemeStart = schemeStart(text, cursor, separator);
            int authorityStart = separator + SCHEME_SEPARATOR.length();
            int candidateEnd = candidateEnd(text, authorityStart);
            int urlEnd = trimTrailing(text, schemeStart, authorityStart, candidateEnd);
            if (!maskUrl(text, authorityStart, urlEnd, result)) {
                return null;
            }

            cursor = candidateEnd;
            separator = text.indexOf(SCHEME_SEPARATOR, cursor);
        }

        return result.finish();
    }

    private static int schemeStart(String text, int from, int separator) {
        int start = separator;
        while ((start > from) && isSchemeCharacter(text.charAt(start - 1))) {
            start--;
        }

        return start;
    }

    private static int candidateEnd(String text, int authorityStart) {
        boolean inParameters = false;
        boolean inValue = false;
        boolean inFragment = false;
        boolean inAuthority = true;
        for (int end = authorityStart; end < text.length(); end++) {
            char c = text.charAt(end);
            if (isTextDelimiter(c)) {
                return end;
            }

            if (text.startsWith(SCHEME_SEPARATOR, end)) {
                int nextSchemeStart = schemeStart(text, authorityStart, end);
                boolean startsNext = (inFragment && (nextSchemeStart > authorityStart)
                        && isFragmentUrlSeparator(text.charAt(nextSchemeStart - 1)))
                        || (!inFragment && !inValue);
                if (startsNext && (!inAuthority || isHostAndPort(text, authorityStart, nextSchemeStart))) {
                    return nextSchemeStart;
                }
            }

            if ((c == '/') || (c == '?') || (c == '#')) {
                inAuthority = false;
            }

            switch (c) {
                case '?' -> inParameters = true;
                case '#' -> {
                    inParameters = true;
                    inValue = false;
                    inFragment = true;
                }
                case '=' -> inValue |= inParameters;
                case '&' -> inValue = false;
                default -> {
                }
            }
        }

        return text.length();
    }

    private static int trimTrailing(String text, int schemeStart, int authorityStart, int candidateEnd) {
        int parentheses = balance(text, authorityStart, candidateEnd, '(', ')');
        int brackets = balance(text, authorityStart, candidateEnd, '[', ']');
        int braces = balance(text, authorityStart, candidateEnd, '{', '}');
        boolean quoted = (schemeStart > 0) && (text.charAt(schemeStart - 1) == '\'');
        boolean doubleQuoted = (schemeStart > 0) && (text.charAt(schemeStart - 1) == '"');
        int urlEnd = candidateEnd;
        while (urlEnd > authorityStart) {
            char c = text.charAt(urlEnd - 1);
            if ((c == ')') && (parentheses < 0)) {
                parentheses++;
            } else if ((c == ']') && (brackets < 0)) {
                brackets++;
            } else if ((c == '}') && (braces < 0)) {
                braces++;
            } else if (!isTrailingPunctuation(c)
                    && !((c == '\'') && (quoted || (candidateEnd < text.length())))
                    && !((c == '"') && doubleQuoted)) {
                break;
            }

            urlEnd--;
        }

        return ((urlEnd < candidateEnd) && (text.charAt(urlEnd - 1) == '=')) ? candidateEnd : urlEnd;
    }

    private static boolean maskUrl(String text, int authorityStart, int urlEnd, MaskedText result) {
        int authorityEnd = indexOfAny(text, authorityStart, urlEnd, "/?#");
        int userinfoEnd = lastIndexOf(text, '@', authorityStart, authorityEnd);
        int hostStart = (userinfoEnd >= 0) ? (userinfoEnd + 1) : authorityStart;
        if (!isHostAndPort(text, hostStart, authorityEnd)) {
            return false;
        }

        if (userinfoEnd >= 0) {
            result.mask(authorityStart, userinfoEnd);
        }

        int separator = indexOfAny(text, authorityEnd, urlEnd, "?#");
        while (separator < urlEnd) {
            if (text.charAt(separator) == '#') {
                result.mask(separator + 1, urlEnd);
                break;
            }

            int parameterEnd = indexOfAny(text, separator + 1, urlEnd, "&#");
            int valueStart = indexOfAny(text, separator + 1, parameterEnd, "=") + 1;
            if (valueStart <= parameterEnd) {
                result.mask(valueStart, parameterEnd);
            }

            separator = parameterEnd;
        }

        return true;
    }

    private static boolean isHostAndPort(String text, int start, int end) {
        int hostEnd;
        if ((start < end) && (text.charAt(start) == '[')) {
            int close = indexOfAny(text, start + 1, end, "]");
            if ((close >= end) || !isIpLiteral(text, start + 1, close)) {
                return false;
            }

            hostEnd = close + 1;
        } else {
            hostEnd = indexOfAny(text, start, end, ":");
        }

        if (hostEnd > end) {
            return false;
        }

        if (hostEnd == end) {
            return true;
        }

        return (text.charAt(hostEnd) == ':') && isPort(text, hostEnd + 1, end);
    }

    private static boolean isIpLiteral(String text, int start, int end) {
        if (start == end) {
            return false;
        }

        boolean colon = false;
        for (int index = start; index < end; index++) {
            char c = text.charAt(index);
            if (c == ':') {
                colon = true;
            } else if ((c != '.') && !isHexDigit(c)) {
                return false;
            }
        }

        return colon;
    }

    private static boolean isHexDigit(char c) {
        return ((c >= '0') && (c <= '9')) || ((c >= 'a') && (c <= 'f')) || ((c >= 'A') && (c <= 'F'));
    }

    private static boolean isPort(String text, int start, int end) {
        if ((start == end) || ((end - start) > MAX_PORT_DIGITS)) {
            return false;
        }

        for (int index = start; index < end; index++) {
            char c = text.charAt(index);
            if ((c < '0') || (c > '9')) {
                return false;
            }
        }

        return true;
    }

    private static int balance(String text, int start, int end, char opening, char closing) {
        int balance = 0;
        for (int index = start; index < end; index++) {
            char c = text.charAt(index);
            if (c == opening) {
                balance++;
            } else if (c == closing) {
                balance--;
            }
        }

        return balance;
    }

    private static int indexOfAny(String text, int start, int end, String characters) {
        for (int index = start; index < end; index++) {
            if (characters.indexOf(text.charAt(index)) >= 0) {
                return index;
            }
        }

        return end;
    }

    @SuppressWarnings("SameParameterValue")
    private static int lastIndexOf(String text, char target, int start, int end) {
        for (int index = end - 1; index >= start; index--) {
            if (text.charAt(index) == target) {
                return index;
            }
        }

        return -1;
    }

    private static boolean isSchemeCharacter(char c) {
        return ((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z')) || ((c >= '0') && (c <= '9'))
                || (c == '+') || (c == '-') || (c == '.');
    }

    private static boolean isFragmentUrlSeparator(char c) {
        return (c == ',') || (c == ';') || (c == '\'');
    }

    private static boolean isTextDelimiter(char c) {
        return Character.isWhitespace(c);
    }

    private static boolean isTrailingPunctuation(char c) {
        return (c == ',') || (c == '.') || (c == ';') || (c == ':');
    }

    private static final class MaskedText {

        private final String original;
        private StringBuilder output;
        private int copiedUntil;

        private MaskedText(String original) {
            this.original = original;
        }

        private void mask(int start, int end) {
            if ((start == end) || ((end - start == MASK.length()) && original.startsWith(MASK, start))) {
                return;
            }

            if (output == null) {
                output = new StringBuilder(original.length());
            }

            output.append(original, copiedUntil, start).append(MASK);
            copiedUntil = end;
        }

        @Nullable
        private String finish() {
            if (output == null) {
                return original;
            }

            if (output.length() + original.length() - copiedUntil > MAX_SCAN_LENGTH) {
                return null;
            }

            return output.append(original, copiedUntil, original.length()).toString();
        }
    }
}
