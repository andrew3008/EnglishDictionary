package space.br1440.platform.tracing.core.utils;

import lombok.experimental.UtilityClass;

import java.util.List;

@UtilityClass
public final class ListUtils {

    public static boolean isNullOrEmpty(List<?> values) {
        return (values == null) || values.isEmpty();
    }

    public static boolean isNotEmpty(List<?> values) {
        return !isNullOrEmpty(values);
    }
}
