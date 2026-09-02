package space.br1440.platform.tracing.runtime.propagation;

import jakarta.annotation.Nullable;

import space.br1440.platform.common.util.StringUtils;

import lombok.experimental.UtilityClass;

@UtilityClass
final class CanonicalIdGrammar {

    static final int MAX_LENGTH = 128;

    static boolean isCanonical(@Nullable String value) {
        if (StringUtils.isNullOrEmpty(value) || value.length() > MAX_LENGTH) {
            return false;
        }

        for (int index = 0; index < value.length(); index++) {
            if (!isCanonicalChar(value.charAt(index))) {
                return false;
            }
        }

        return true;
    }

    private static boolean isCanonicalChar(char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '_'
                || character == '-';
    }
}
