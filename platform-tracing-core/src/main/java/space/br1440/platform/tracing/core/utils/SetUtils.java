package space.br1440.platform.tracing.core.utils;

import lombok.experimental.UtilityClass;

import java.util.Set;

@UtilityClass
public final class SetUtils {

    public static boolean isNullOrEmpty(Set<?> set) {
        return set == null || set.isEmpty();
    }

    public static boolean isNotEmpty(Set<?> set) {
        return !isNullOrEmpty(set);
    }
}
