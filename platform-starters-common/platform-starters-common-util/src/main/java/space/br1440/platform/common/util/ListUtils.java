package space.br1440.platform.common.util;

import java.util.List;
import jakarta.annotation.Nullable;

public final class ListUtils {

    private ListUtils() {
    }

    public static boolean isNullOrEmpty(@Nullable List<?> values) {
        return (values == null) || values.isEmpty();
    }
}
