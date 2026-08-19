package space.br1440.platform.common.util;

import jakarta.annotation.Nullable;

public final class StringUtils {

    private StringUtils() {
    }

    public static boolean isNullOrEmpty(@Nullable String value) {
        return (value == null) || value.isEmpty();
    }

    public static boolean isNullOrBlank(@Nullable String value) {
        return (value == null) || value.isBlank();
    }

    public static boolean hasText(@Nullable String value) {
        return !isNullOrBlank(value);
    }
}
