package space.br1440.platform.common.util;

import jakarta.annotation.Nullable;

public final class ArrayUtils {

    private ArrayUtils() {
    }

    public static <T> boolean isNullOrEmpty(@Nullable T[] values) {
        return (values == null) || (values.length == 0);
    }
}
