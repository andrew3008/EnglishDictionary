package space.br1440.platform.tracing.core.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ArrayUtils {

    public static <T> boolean isNullOrEmpty(T[] values) {
        return values == null || values.length == 0;
    }

    public static <T> boolean isNotEmpty(T[] values) {
        return !isNullOrEmpty(values);
    }
}
