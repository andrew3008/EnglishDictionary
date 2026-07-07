package space.br1440.platform.tracing.api.span.sanitize;

import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;

import java.util.regex.Pattern;

/**
 * Санитайзер SQL для значения {@code db.statement}: убирает литералы (PII/секреты), оставляя
 * структуру запроса. НЕ предназначен для имени span'а — имя строится из
 * {@code {operation} {collection}} ({@code PlatformSpanNameBuilder}), а не из сырого SQL.
 */
@UtilityClass
public final class SqlSanitizer {

    /** Строковые литералы в одинарных кавычках (с экранированием ''). */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    /** Числовые литералы (целые/дробные). */
    private static final Pattern NUMERIC_LITERAL = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");

    /** Повторяющиеся плейсхолдеры в IN-списках: (?, ?, ?) -> (?). */
    private static final Pattern IN_LIST = Pattern.compile("\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)");

    private static final int MAX_LENGTH = 1000;

    @Nullable
    public static String sanitize(@Nullable String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }

        String result = STRING_LITERAL.matcher(sql).replaceAll("?");
        result = NUMERIC_LITERAL.matcher(result).replaceAll("?");
        result = IN_LIST.matcher(result).replaceAll("(?)");
        result = result.strip();
        return (result.length() > MAX_LENGTH) ? result.substring(0, MAX_LENGTH) : result;
    }
}
