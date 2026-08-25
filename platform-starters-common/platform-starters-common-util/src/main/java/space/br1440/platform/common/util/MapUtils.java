package space.br1440.platform.common.util;

import java.util.Map;
import jakarta.annotation.Nullable;

public final class MapUtils {

    private MapUtils() {
    }

    public static boolean isNullOrEmpty(@Nullable Map<?, ?> map) {
        return (map == null) || map.isEmpty();
    }

    public static <K, V> boolean containsKey(@Nullable Map<K, V> map, @Nullable K key) {
        return (map != null) && map.containsKey(key);
    }

    @Nullable
    public static <K, V> V getOrDefault(@Nullable Map<K, V> map,
                                        @Nullable K key,
                                        @Nullable V defaultValue) {
        if (isNullOrEmpty(map)) {
            return defaultValue;
        }

        return map.getOrDefault(key, defaultValue);
    }

    public static int size(@Nullable Map<?, ?> map) {
        return (map == null) ? 0 : map.size();
    }
}
