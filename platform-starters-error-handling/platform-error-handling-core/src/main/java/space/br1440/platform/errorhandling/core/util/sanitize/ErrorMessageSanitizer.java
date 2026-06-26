package space.br1440.platform.errorhandling.core.util.sanitize;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Санитайзер для пользовательских строк (URL, имя HTTP-метода, имя параметра, content-type),
 * включаемых в публичное поле {@code ErrorEntry.message} и улетающих клиенту в HTTP-ответе.
 *
 * <p>Поле {@code message} читается человеком, поэтому тут применяется более «мягкий» pipeline,
 * чем для произвольного {@code causeMessage} в diagnostic-metadata: сохраняется ASCII-структура,
 * убираются опасные конструкции.
 *
 * <h2>Покрытие</h2>
 * <ol>
 *   <li><b>CWE-22 (Path Traversal)</b> — обнаружение последовательностей {@code ..} и их
 *       URL-encoded вариантов ({@code %2E%2E}, {@code %2e%2e}). Если обнаружены — путь
 *       заменяется маркером {@link #INVALID_PATH_MARKER}, иначе пропускается дальше.</li>
 *   <li><b>Нормализация структуры пути</b> — динамические сегменты ({@code id}, UUID, hex)
 *       заменяются на {@code {id}} через {@link PathSanitizer#sanitize(String)}; query/fragment
 *       обрезаются. Это защищает от утечки PII в URL и снижает кардинальность логов.</li>
 *   <li><b>CWE-117 (Log Injection)</b> и Trojan-Source bidi-overrides — удаляются через
 *       {@link LogSanitizer#sanitize(String, int)} перед возвратом строки.</li>
 *   <li><b>Длина</b> — обрезается с маркером {@code [TRUNCATED]} для защиты от log-DoS
 *       и unbounded response body.</li>
 * </ol>
 *
 * <h2>Контракт</h2>
 * <ul>
 *   <li>{@code null} и blank возвращаются как {@code "<unknown>"}: {@code message} обязан
 *       быть non-null (валидируется в {@code ErrorEntry.builder}).</li>
 *   <li>Все методы thread-safe: pre-compiled regex, без shared state.</li>
 * </ul>
 *
 * @see PathSanitizer URL/path normalization
 * @see LogSanitizer Log-injection и Trojan-Source defense
 */
@UtilityClass
public final class ErrorMessageSanitizer {

    /** Дефолтный лимит для URL/идентификаторов в публичном {@code message}. */
    public static final int MAX_URL_LENGTH = 200;

    /** Дефолтный лимит для коротких токенов (имя метода, имя параметра, content-type). */
    public static final int MAX_TOKEN_LENGTH = 64;

    /** Маркер при обнаружении path-traversal (CWE-22). */
    public static final String INVALID_PATH_MARKER = "<invalid-path>";

    /** Маркер для null/blank входов. */
    public static final String UNKNOWN_MARKER = "<unknown>";

    /**
     * Path-traversal паттерн: ловит как литеральные {@code ..}, так и URL-encoded формы
     * (case-insensitive). Любое совпадение → отбрасываем весь путь.
     *
     * <p>Намеренно НЕ нормализуем (например, через {@code URI.normalize()}) — после
     * нормализации {@code /api/../etc/passwd} превратится в {@code /etc/passwd}, что
     * само по себе утечка (раскрывает наличие attempted attack). Полный reject лучше.
     */
    private static final Pattern PATH_TRAVERSAL = Pattern.compile(
            "(?:\\.\\.|%2[Ee]%2[Ee])");

    /**
     * Санитизирует URL/путь для включения в публичное {@code message}:
     * path-traversal → маркер; иначе нормализация через {@link PathSanitizer} +
     * {@link LogSanitizer}.
     */
    @NonNull
    public static String sanitizeUrl(@Nullable String url) {
        if (!StringUtils.hasText(url)) {
            return UNKNOWN_MARKER;
        }

        if (PATH_TRAVERSAL.matcher(url).find()) {
            return INVALID_PATH_MARKER;
        }

        String normalized = PathSanitizer.sanitize(url, MAX_URL_LENGTH);
        return LogSanitizer.sanitize(normalized, MAX_URL_LENGTH);
    }

    /**
     * Санитизирует короткий токен (HTTP-метод, имя параметра, content-type),
     * пропускает только ASCII-printable + базовые разделители, обрезает длину,
     * удаляет CR/LF/Trojan-Source через {@link LogSanitizer}.
     */
    @NonNull
    public static String sanitizeToken(@Nullable String token) {
        if (!StringUtils.hasText(token)) {
            return UNKNOWN_MARKER;
        }

        return LogSanitizer.sanitize(token.trim(), MAX_TOKEN_LENGTH);
    }

    /**
     * Нормализует HTTP-метод к upper-case. Полезно для консистентности сообщений
     * (например, не выдавать клиенту его же {@code "patch"} вместо {@code "PATCH"}).
     */
    @NonNull
    public static String sanitizeHttpMethod(@Nullable String method) {
        String safe = sanitizeToken(method);
        return UNKNOWN_MARKER.equals(safe) ? safe : safe.toUpperCase(Locale.ROOT);
    }
}
