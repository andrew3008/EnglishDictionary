package space.br1440.platform.common.util;

import jakarta.annotation.Nullable;

/**
 * Null-безопасные проверки пустоты и blank-состояния строки.
 * <p>
 * {@link #isNullOrEmpty(String)} заменяет {@code value == null || value.isEmpty()}.
 * {@link #isNullOrBlank(String)} заменяет {@code value == null || value.isBlank()}.
 * Обработка {@code null} видна в имени метода. {@code null} — валидный вход, а не ошибка:
 * методы не бросают {@link NullPointerException}.
 * <p>
 * {@code blank} — модель JDK {@link String#isBlank()}: пустая строка и строка из одних
 * whitespace-символов по {@link Character#isWhitespace(int)}. Это не отдельный
 * {@code isNullOrWhiteSpace}: то же поведение, алиас не вводится.
 * <p>
 * Положительной формы ({@code isNotEmpty}, {@code hasText}) нет: вызывающий пишет
 * {@code !isNullOrEmpty(value)}.
 * <p>
 * Простое имя {@code StringUtils} совпадает с Apache Commons Lang. В одном исходном файле
 * допускается только один {@code StringUtils}; второй импортируется по FQCN
 * {@code space.br1440.platform.common.util.StringUtils}.
 */
public final class StringUtils {

    private StringUtils() {
    }

    /**
     * Возвращает {@code true}, если {@code value == null || value.isEmpty()}.
     *
     * @param value строка или {@code null}
     * @return {@code true} для {@code null} и {@code ""}; {@code false} для {@code "   "} и непустой строки
     */
    public static boolean isNullOrEmpty(@Nullable String value) {
        return value == null || value.isEmpty();
    }

    /**
     * Возвращает {@code true}, если {@code value == null || value.isBlank()}.
     *
     * @param value строка или {@code null}
     * @return {@code true} для {@code null}, {@code ""} и whitespace-only; {@code false} иначе
     */
    public static boolean isNullOrBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
