package space.br1440.platform.tracing.runtime.scrubbing;

import java.util.Objects;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class UrlCredentialMasker {

    private static final int MAX_TEXT_LENGTH = 4_096;
    private static final int MAX_PORT_DIGITS = 5;
    private static final String SCHEME_SEPARATOR = "://";
    private static final String MASK = "***";
    private static final String URL_SEPARATORS = ",;'";
    private static final String ENCLOSING_OPENERS = "([{'\"";
    private static final String ENCLOSING_CLOSERS = ")]}'\"";

    @Nullable
    public static String mask(@Nonnull String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_TEXT_LENGTH) {
            return null;
        }

        MaskedText result = new MaskedText(text);
        int cursor = 0;
        for (int colon = text.indexOf(':'); colon >= 0; colon = text.indexOf(':', cursor)) {
            int schemeStart = schemeStart(text, cursor, colon);
            if (!text.startsWith(SCHEME_SEPARATOR, colon)) {
                if (isBrokenSchemeSeparator(text, schemeStart, colon)) {
                    return null;
                }

                cursor = colon + 1;
                continue;
            }

            int authorityStart = colon + SCHEME_SEPARATOR.length();
            int candidateEnd = candidateEnd(text, authorityStart);
            int urlEnd = trimTrailing(text, schemeStart, authorityStart, candidateEnd);
            if (!maskUrl(text, authorityStart, urlEnd, result)) {
                return null;
            }

            cursor = candidateEnd;
        }

        return result.finish();
    }

    private static boolean isBrokenSchemeSeparator(String text, int schemeStart, int colon) {
        boolean http = isHttpScheme(text, schemeStart, colon);
        int next = colon + 1;
        if (http && ((next == text.length()) || !isTextDelimiter(text.charAt(next)))) {
            return true;
        }

        if (schemeStart == colon) {
            return false;
        }

        while ((next < text.length()) && (((text.charAt(next) == ' ') && http) || isInvisible(text.charAt(next)))) {
            next++;
        }

        return (next > colon + 1) && text.startsWith("//", next);
    }

    private static boolean isHttpScheme(String text, int start, int colon) {
        int length = colon - start;
        return ((length == 4) && text.regionMatches(true, start, "http", 0, 4))
                || ((length == 5) && text.regionMatches(true, start, "https", 0, 5));
    }

    private static int schemeStart(String text, int from, int colon) {
        int start = colon;
        while ((start > from) && isSchemeCharacter(text.charAt(start - 1))) {
            start--;
        }

        return start;
    }

    private static int candidateEnd(String text, int authorityStart) {
        Part part = Part.AUTHORITY;
        for (int index = authorityStart; index < text.length(); index++) {
            char c = text.charAt(index);
            if (isTextDelimiter(c)) {
                return index;
            }

            if (text.startsWith(SCHEME_SEPARATOR, index)) {
                int nextStart = schemeStart(text, authorityStart, index);
                if (startsNextUrl(text, part, authorityStart, nextStart)) {
                    return (URL_SEPARATORS.indexOf(text.charAt(nextStart - 1)) >= 0) ? nextStart - 1 : nextStart;
                }
            }

            part = part.next(c);
        }

        return text.length();
    }

    private static boolean startsNextUrl(String text, Part part, int authorityStart, int nextStart) {
        return switch (part) {
            case AUTHORITY -> isHostAndPort(text, authorityStart, nextStart);
            case PATH -> true;
            case PARAMETERS -> false;
        };
    }

    private static int trimTrailing(String text, int schemeStart, int authorityStart, int candidateEnd) {
        int enclosing = (schemeStart > 0) ? ENCLOSING_OPENERS.indexOf(text.charAt(schemeStart - 1)) : -1;
        if (enclosing >= 0) {
            char closing = ENCLOSING_CLOSERS.charAt(enclosing);
            int end = candidateEnd;
            while ((end > authorityStart) && (text.charAt(end - 1) != closing)
                    && isTrailingPunctuation(text.charAt(end - 1))) {
                end--;
            }

            if ((end > authorityStart) && (text.charAt(end - 1) == closing)) {
                return end - 1;
            }
        }

        if (indexOfAny(text, authorityStart, candidateEnd, "?#") < candidateEnd) {
            return candidateEnd;
        }

        int end = candidateEnd;
        while ((end > authorityStart) && isTrailingPunctuation(text.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private static boolean maskUrl(String text, int authorityStart, int urlEnd, MaskedText result) {
        int authorityEnd = indexOfAny(text, authorityStart, urlEnd, "/?#");
        int userinfoEnd = userinfoEnd(text, authorityStart, authorityEnd);
        int hostStart = (userinfoEnd >= 0) ? (userinfoEnd + 1) : authorityStart;
        if (!isHostAndPort(text, hostStart, authorityEnd)) {
            return false;
        }

        if ((userinfoEnd >= 0) && !result.mask(authorityStart, userinfoEnd)) {
            return false;
        }

        return maskParameters(text, indexOfAny(text, authorityEnd, urlEnd, "?#"), urlEnd, result);
    }

    private static int userinfoEnd(String text, int authorityStart, int authorityEnd) {
        for (int index = authorityEnd - 1; index >= authorityStart; index--) {
            if (text.charAt(index) == '@') {
                return index;
            }
        }

        return -1;
    }

    private static boolean maskParameters(String text, int start, int end, MaskedText result) {
        int separator = start;
        while (separator < end) {
            if (text.charAt(separator) == '#') {
                return result.mask(separator + 1, end);
            }

            int parameterEnd = indexOfAny(text, separator + 1, end, "&#");
            if (!result.mask(valueStart(text, separator + 1, parameterEnd), parameterEnd)) {
                return false;
            }
            separator = parameterEnd;
        }
        return true;
    }

    private static int valueStart(String text, int parameterStart, int parameterEnd) {
        int equals = indexOfAny(text, parameterStart, parameterEnd, "=");
        boolean plainKey = (indexOfAny(text, parameterStart, equals, "%:/?") == equals);
        return ((equals < parameterEnd) && plainKey) ? (equals + 1) : parameterStart;
    }

    private static boolean isHostAndPort(String text, int start, int end) {
        if (start == end) {
            return false;
        }

        int hostEnd;
        if (text.charAt(start) == '[') {
            int close = indexOfAny(text, start + 1, end, "]");
            if ((close == end) || !isIpLiteral(text, start + 1, close)) {
                return false;
            }

            hostEnd = close + 1;
        } else {
            hostEnd = indexOfAny(text, start, end, ":");
            if ((hostEnd == start) || !isRegisteredName(text, start, hostEnd)) {
                return false;
            }
        }

        return (hostEnd == end) || ((text.charAt(hostEnd) == ':') && isPort(text, hostEnd + 1, end));
    }

    private static boolean isRegisteredName(String text, int start, int end) {
        for (int index = start; index < end; index++) {
            char c = text.charAt(index);
            if (Character.isISOControl(c) || ("%\\\"'`<>[]".indexOf(c) >= 0)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isIpLiteral(String text, int start, int end) {
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

    private static int indexOfAny(String text, int start, int end, String characters) {
        for (int index = start; index < end; index++) {
            if (characters.indexOf(text.charAt(index)) >= 0) {
                return index;
            }
        }

        return end;
    }

    private static boolean isSchemeCharacter(char c) {
        return ((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z')) || ((c >= '0') && (c <= '9'))
                || (c == '+') || (c == '-') || (c == '.');
    }

    private static boolean isTextDelimiter(char c) {
        return (c == ' ') || (c == '\t') || (c == '\n') || (c == '\r');
    }

    private static boolean isInvisible(char c) {
        return (c != ' ')
                && (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)
                || (Character.getType(c) == Character.FORMAT));
    }

    private static boolean isTrailingPunctuation(char c) {
        return (c == ',') || (c == '.') || (c == ';') || (c == ':') || (c == '\'');
    }

    private enum Part {
        AUTHORITY,
        PATH,
        PARAMETERS;

        private Part next(char c) {
            if ((this == PARAMETERS) || (c == '#') || (c == '?')) {
                return PARAMETERS;
            }

            return ((c == '/') && (this == AUTHORITY)) ? PATH : this;
        }
    }

    private static final class MaskedText {

        private final String original;
        private StringBuilder output;
        private int copiedUntil;

        private MaskedText(String original) {
            this.original = original;
        }

        private boolean mask(int start, int end) {
            if ((start == end) || ((end - start == MASK.length()) && original.startsWith(MASK, start))) {
                return true;
            }

            int length = (output == null) ? 0 : output.length();
            if ((start - copiedUntil + MASK.length()) > (MAX_TEXT_LENGTH - length)) {
                return false;
            }

            if (output == null) {
                output = new StringBuilder(MAX_TEXT_LENGTH);
            }

            output.append(original, copiedUntil, start).append(MASK);
            copiedUntil = end;
            return true;
        }

        @Nullable
        private String finish() {
            if (output == null) {
                return original;
            }

            if ((original.length() - copiedUntil) > (MAX_TEXT_LENGTH - output.length())) {
                return null;
            }

            return output.append(original, copiedUntil, original.length()).toString();
        }
    }
}
