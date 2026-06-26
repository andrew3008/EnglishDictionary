package space.br1440.platform.errorhandling.core.util.http;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Парсер HTTP-заголовка {@code Retry-After} согласно
 * <a href="https://www.rfc-editor.org/rfc/rfc7231#section-7.1.3">RFC 7231 §7.1.3</a>.
 *
 * <p>Заголовок допускает два формата:
 * <ul>
 *   <li><b>delay-seconds</b> — неотрицательное целое число секунд (e.g. {@code 120})</li>
 *   <li><b>HTTP-date</b> — абсолютная дата в формате IMF-fixdate (e.g. {@code Fri, 31 Dec 1999 23:59:59 GMT})</li>
 * </ul>
 *
 * <p>Если значение невалидно или отрицательно, возвращается {@code null} —
 * вызывающий код интерпретирует это как «задержка не определена сервером».
 */
@UtilityClass
public final class RetryAfterHeaderParser {

    private static final long MAX_RETRY_AFTER_SECONDS = 86_400;

    @Nullable
    public static Duration parse(@Nullable String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }

        String trimmed = headerValue.trim();

        Duration delaySeconds = tryParseSeconds(trimmed);
        if (delaySeconds != null) {
            return delaySeconds;
        }

        return tryParseHttpDate(trimmed);
    }

    @Nullable
    private static Duration tryParseSeconds(@NonNull String value) {
        try {
            long seconds = Long.parseLong(value);
            if (seconds < 0 || seconds > MAX_RETRY_AFTER_SECONDS) {
                return null;
            }

            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Duration tryParseHttpDate(@NonNull String value) {
        try {
            ZonedDateTime retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration delay = Duration.between(ZonedDateTime.now(), retryAt);
            if (delay.isNegative() || delay.toSeconds() > MAX_RETRY_AFTER_SECONDS) {
                return null;
            }

            return delay;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
