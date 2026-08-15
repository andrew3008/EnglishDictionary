package space.br1440.platform.common.util;

import java.util.List;

import jakarta.annotation.Nullable;

/**
 * Null-безопасная проверка пустоты {@link List}.
 * <p>
 * {@link #isNullOrEmpty(List)} заменяет {@code values == null || values.isEmpty()}.
 * Обработка {@code null} видна в имени метода. {@code null} — валидный вход, а не ошибка:
 * метод не бросает {@link NullPointerException}.
 * <p>
 * Контракт типизирован по {@link List}, а не по {@code Collection}: отдельный {@code CollectionUtils}
 * в модуле не вводится.
 * <p>
 * Положительной формы нет: вызывающий пишет {@code !isNullOrEmpty(values)}.
 */
public final class ListUtils {

    private ListUtils() {
    }

    /**
     * Возвращает {@code true}, если {@code values == null || values.isEmpty()}.
     *
     * @param values список или {@code null}
     * @return {@code true} для {@code null} и пустого списка; {@code false}, если есть хотя бы один элемент,
     *         в том числе {@code null}-элемент
     */
    public static boolean isNullOrEmpty(@Nullable List<?> values) {
        return values == null || values.isEmpty();
    }
}
