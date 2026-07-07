package space.br1440.platform.tracing.core.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class StringUtils {

    public static boolean isNullOrBlank(String value) {
        return value == null || value.isBlank();
    }

    public static boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }

    public static boolean isNotEmpty(String value) {
        return !isNullOrEmpty(value);
    }
}
