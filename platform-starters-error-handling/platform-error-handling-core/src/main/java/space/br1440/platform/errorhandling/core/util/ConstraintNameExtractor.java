package space.br1440.platform.errorhandling.core.util;

import lombok.experimental.UtilityClass;
import org.springframework.lang.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Извлекает имя constraint из сообщений исключений БД.
 *
 * <p>Поддерживаемые форматы (приоритет — первый match):
 * <ul>
 *   <li>PostgreSQL: {@code constraint "constraint_name"}, {@code foreign key constraint "..."}</li>
 *   <li>MySQL: {@code for key 'table.constraint_name'}, {@code for key 'constraint_name'}</li>
 *   <li>H2: {@code violation: "CONSTRAINT_NAME"}</li>
 * </ul>
 *
 * <p>Имена constraint — schema-level metadata, не данные строк (CWE-209 safe).
 *
 * <p>Используется в {@code DataAccessExceptionStrategy} и {@code JooqExceptionStrategy}.
 */
@UtilityClass
public final class ConstraintNameExtractor {

    private static final Pattern[] CONSTRAINT_PATTERNS = {
            Pattern.compile("constraint\\s+[\"'`]?([A-Za-z0-9_$.]+)[\"'`]?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("for key '(?:[^.]+\\.)?([^']+)'"),
            Pattern.compile("violation:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
    };

    @Nullable
    public static String extract(@Nullable String message) {
        if (message == null) {
            return null;
        }

        for (Pattern pattern : CONSTRAINT_PATTERNS) {
            Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        return null;
    }
}
