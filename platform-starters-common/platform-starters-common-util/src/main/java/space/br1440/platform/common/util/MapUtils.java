package space.br1440.platform.common.util;

import java.util.Map;

import jakarta.annotation.Nullable;

/**
 * Null-безопасные операции над {@link Map}: пустота, наличие ключа, чтение со значением по умолчанию, размер.
 * <p>
 * {@link #isNullOrEmpty(Map)} заменяет {@code map == null || map.isEmpty()}.
 * Обработка {@code null}-карты видна в имени метода. {@code null} — валидный вход, а не ошибка:
 * методы не бросают {@link NullPointerException} из-за {@code null}-карты.
 * <p>
 * {@link #getOrDefault(Map, Object, Object)} повторяет имя {@link Map#getOrDefault(Object, Object)}
 * и добавляет терпимость к {@code null}-карте. Если ключ присутствует со значением {@code null},
 * возвращается это {@code null}, а не {@code defaultValue}.
 * <p>
 * Положительной формы {@code isNotEmpty} нет: вызывающий пишет {@code !isNullOrEmpty(map)}.
 */
public final class MapUtils {

    private MapUtils() {
    }

    /**
     * Возвращает {@code true}, если {@code map == null || map.isEmpty()}.
     *
     * @param map карта или {@code null}
     * @return {@code true} для {@code null} и пустой карты; {@code false}, если есть хотя бы одна запись
     */
    public static boolean isNullOrEmpty(@Nullable Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    /**
     * Возвращает {@code true}, если карта не {@code null} и содержит {@code key}.
     * <p>
     * Для {@code null}-карты результат {@code false}. Исключения конкретной реализации карты
     * (например, {@link NullPointerException} на {@code null}-ключе у {@link java.util.TreeMap})
     * не перехватываются.
     *
     * @param map карта или {@code null}
     * @param key ключ, в том числе {@code null}, если реализация карты это допускает
     * @return {@code false} для {@code null}-карты; иначе результат {@link Map#containsKey(Object)}
     */
    public static <K, V> boolean containsKey(@Nullable Map<K, V> map, @Nullable K key) {
        return map != null && map.containsKey(key);
    }

    /**
     * Возвращает значение по {@code key} или {@code defaultValue}, если карты нет, она пуста
     * или ключ отсутствует.
     * <p>
     * Метод не подменяет переданный {@code defaultValue} на {@code null}. Если ключ есть
     * и хранит {@code null}, возвращается это хранимое {@code null}.
     *
     * @param map          карта или {@code null}
     * @param key          ключ, в том числе {@code null}, если реализация карты это допускает
     * @param defaultValue значение при отсутствии карты, пустой карте или отсутствии ключа; может быть {@code null}
     * @return найденное значение, хранимый {@code null} или {@code defaultValue}
     */
    @Nullable
    public static <K, V> V getOrDefault(
            @Nullable Map<K, V> map,
            @Nullable K key,
            @Nullable V defaultValue) {
        if (isNullOrEmpty(map)) {
            return defaultValue;
        }
        return map.getOrDefault(key, defaultValue);
    }

    /**
     * Возвращает размер карты или {@code 0}, если карта {@code null}.
     *
     * @param map карта или {@code null}
     * @return {@code 0} для {@code null}; иначе {@link Map#size()}
     */
    public static int size(@Nullable Map<?, ?> map) {
        return map == null ? 0 : map.size();
    }
}
