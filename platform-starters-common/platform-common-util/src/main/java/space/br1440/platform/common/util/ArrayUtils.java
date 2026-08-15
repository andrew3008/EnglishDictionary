package space.br1440.platform.common.util;

import jakarta.annotation.Nullable;

/**
 * Null-безопасная проверка пустоты ссылочного массива.
 * <p>
 * {@link #isNullOrEmpty(Object[])} заменяет {@code values == null || values.length == 0}.
 * Обработка {@code null} видна в имени метода. {@code null} — валидный вход, а не ошибка:
 * метод не бросает {@link NullPointerException}.
 * <p>
 * Принимается только {@code T[]}. Примитивные массивы ({@code int[]}, {@code long[]} и остальные)
 * в v1 не поддерживаются: {@code int[]} не является {@code T[]}.
 * <p>
 * Положительной формы нет: вызывающий пишет {@code !isNullOrEmpty(values)}.
 */
public final class ArrayUtils {

    private ArrayUtils() {
    }

    /**
     * Возвращает {@code true}, если {@code values == null || values.length == 0}.
     *
     * @param values ссылочный массив или {@code null}
     * @return {@code true} для {@code null} и массива длины 0; {@code false}, если есть хотя бы один элемент,
     *         в том числе {@code null}-элемент
     */
    public static <T> boolean isNullOrEmpty(@Nullable T[] values) {
        return values == null || values.length == 0;
    }
}
