package space.br1440.platform.common.util;

import java.util.Set;
import jakarta.annotation.Nullable;

public final class SetUtils {

    private SetUtils() {
    }

    public static boolean isNullOrEmpty(@Nullable Set<?> values) {
        return (values == null) || values.isEmpty();
    }
}
