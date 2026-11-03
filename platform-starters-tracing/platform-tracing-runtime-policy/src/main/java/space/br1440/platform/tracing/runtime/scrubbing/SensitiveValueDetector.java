package space.br1440.platform.tracing.runtime.scrubbing;

import java.util.regex.Pattern;
import jakarta.annotation.Nonnull;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class SensitiveValueDetector {

    private static final String LOCAL_PART_CHAR_CLASS = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]";

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?<!" + LOCAL_PART_CHAR_CLASS + ")" + LOCAL_PART_CHAR_CLASS + "++"
                    + "(?<!://\\*{3})@(?:[A-Za-z0-9-]{1,63}+\\.(?=[A-Za-z0-9-]))++"
                    + "(?:[A-Za-z]{2,63}+|[xX][nN]--[A-Za-z0-9-]{1,59}+)"
                    + "(?![A-Za-z0-9-])"
    );

    public static boolean containsEmail(@Nonnull String text) {
        return text.indexOf('@') >= 0 && EMAIL_PATTERN.matcher(text).find();
    }

    public static boolean containsJwt(@Nonnull String text) {
        int headerStart = text.indexOf("eyJ");
        if (headerStart < 0) {
            return false;
        }

        int segmentStart = headerStart;
        boolean headerBeforeDot = false;
        for (int index = headerStart; index < text.length(); index++) {
            char character = text.charAt(index);
            if (isBase64Url(character)) {
                if (headerStart < 0 && character == 'e' && text.startsWith("eyJ", index)) {
                    headerStart = index;
                }

                continue;
            }

            if (character == '.') {
                if (headerBeforeDot && index > segmentStart) {
                    return true;
                }

                headerBeforeDot = (headerStart >= 0) && ((index - headerStart) > 3);
            } else {
                headerBeforeDot = false;
            }

            segmentStart = index + 1;
            headerStart = -1;
        }

        return false;
    }

    private static boolean isBase64Url(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || character == '_' || character == '-';
    }
}
