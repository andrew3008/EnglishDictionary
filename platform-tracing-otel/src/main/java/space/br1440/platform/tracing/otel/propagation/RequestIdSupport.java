package space.br1440.platform.tracing.otel.propagation;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;

import java.util.UUID;

/**
 * Валидация и генерация correlation id ({@code X-Request-Id}).
 * <p>
 * Security-инварианты (зашиты в код, не конфигурируемы):
 * <ul>
 *   <li>Allowlist символов: {@code [A-Za-z0-9_-]}. Любой символ вне списка (в т.ч. CR/LF,
 *       NUL, пробелы, пунктуация, Unicode) приводит к reject — защита от HTTP response
 *       splitting / log injection (CWE-113).</li>
 *   <li>{@link #MAX_LENGTH} трактуется как reject, а не truncate: значение длиннее лимита
 *       отбрасывается целиком (защита от high-cardinality / oversized id).</li>
 *   <li>Trim-and-accept: ведущие и хвостовые пробелы обрезаются перед валидацией, и при
 *       успешной проверке возвращается именно trimmed-значение (толерантность к buggy-клиентам,
 *       присылающим id с пробелами по краям). Внутренние пробелы недопустимы.</li>
 * </ul>
 * <p>
 * Производительность: zero-allocation на валидном hot path (ручной char-цикл вместо
 * {@code Pattern.matcher()}). Класс не имеет состояния и зависимостей от OTel/Spring/Servlet.
 */
@UtilityClass
public final class RequestIdSupport {

    /** Максимально допустимая длина correlation id. Превышение трактуется как аномалия → reject (не truncate). */
    public static final int MAX_LENGTH = 128;

    /**
     * Возвращает санитизированный incoming или, при его отсутствии/невалидности, сгенерированный UUIDv4.
     * Никогда не возвращает {@code null}.
     *
     * @param incoming сырое значение заголовка; допускается {@code null}
     * @return валидный correlation id (входящий или сгенерированный)
     */
    @Nonnull
    public static String resolve(@Nullable String incoming) {
        String sanitized = sanitizeOrNull(incoming);
        return (sanitized != null) ? sanitized : UUID.randomUUID().toString();
    }

    /**
     * Санитизирует входящий correlation id.
     * <p>
     * Порядок: trim → проверка на пустоту и {@link #MAX_LENGTH} → allowlist {@code [A-Za-z0-9_-]}.
     * Ведущие/хвостовые пробелы обрезаются, и при успешной валидации возвращается trimmed-значение
     * (trim-and-accept). Возвращает {@code null}, если вход {@code null}, пуст после trim,
     * превышает {@link #MAX_LENGTH} или содержит символы вне allowlist.
     *
     * @param raw сырое значение заголовка; допускается {@code null}
     * @return санитизированное (trimmed) значение или {@code null}, если вход невалиден
     */
    @Nullable
    public static String sanitizeOrNull(@Nullable String raw) {
        if (raw == null) {
            return null;
        }

        String t = raw.trim();
        if (t.isEmpty() || (t.length() > MAX_LENGTH)) {
            return null;
        }

        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) {
                return null;
            }
        }

        return t;
    }
}
