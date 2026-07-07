package space.br1440.platform.tracing.core.utils;

import lombok.experimental.UtilityClass;

import java.util.Map;

@UtilityClass
public final class MapUtils {

    public static boolean isNullOrEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    public static boolean isNotEmpty(Map<?, ?> map) {
        return !isNullOrEmpty(map);
    }

    public static <K, V> boolean containsKey(Map<K, V> map, K key) {
        return map != null && map.containsKey(key);
    }

    public static <K, V> V getOrDefault(Map<K, V> map, K key, V defaultValue) {
        if (isNullOrEmpty(map)) {
            return defaultValue;
        }

        return map.getOrDefault(key, defaultValue);
    }

    public static int size(Map<?, ?> map) {
        return map == null ? 0 : map.size();
    }
}
