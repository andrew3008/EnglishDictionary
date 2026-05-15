package space.br1440.platform.errorhandling.core.util.sanitize;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.util.regex.Pattern;

/**
 * Унифицированный sanitizer для OAuth2 / JWT диагностических сообщений.
 *
 * <p>Используется во всех OAuth2-стратегиях (root-cause и top-level) для устранения
 * дублирования redaction-паттернов и обеспечения единого формата маркеров между
 * {@code OAuth2AuthorizationExceptionStrategy} и {@code OAuth2ClientExceptionStrategy}.
 *
 * <h2>Покрытие redaction</h2>
 * <ol>
 *   <li><b>Bearer-токены</b> — case-insensitive: {@code Bearer eyJ...} →
 *       {@code Bearer [REDACTED]}.</li>
 *   <li><b>Token-параметры</b> — {@code access_token}, {@code refresh_token},
 *       {@code id_token}, {@code client_secret}, {@code assertion},
 *       {@code client_assertion} с разделителями {@code =} или {@code :} →
 *       {@code <name>=[REDACTED]}.</li>
 *   <li><b>Compact JWT</b> — три сегмента base64url, разделённые точкой
 *       (минимум 8 символов в каждом, чтобы исключить ложные срабатывания
 *       на словах вроде {@code "expression"}) → {@code [JWT_REDACTED]}.</li>
 * </ol>
 *
 * <h2>Защита от log-injection (CWE-117) и Trojan-Source (CVE-2021-42574)</h2>
 * <p>Через {@link LogSanitizer#sanitize(String, int)} удаляются {@code \r}, {@code \n},
 * {@code \t}, {@code NUL} и Unicode bidirectional override (U+202A..U+202E, U+2066..U+2069).
 * Это гарантирует, что любая {@code diagnosticMetadata}-запись остаётся одной строкой
 * лога и не подделывает дополнительные строки.
 *
 * <h2>Порядок применения</h2>
 * <p>Сначала redaction (чтобы маркеры {@code [REDACTED]}/{@code [JWT_REDACTED]} были
 * вставлены до усечения), затем log-injection scrub, затем truncate. Маркеры состоят
 * из ASCII и не пересекаются с запрещёнными символами {@link LogSanitizer}.
 *
 * @see <a href="https://cwe.mitre.org/data/definitions/209.html">CWE-209</a>
 * @see <a href="https://cwe.mitre.org/data/definitions/312.html">CWE-312</a>
 * @see <a href="https://cwe.mitre.org/data/definitions/117.html">CWE-117</a>
 */
@UtilityClass
public final class OAuth2DiagnosticSanitizer {

    public static final String REDACTED = "[REDACTED]";
    public static final String JWT_REDACTED = "[JWT_REDACTED]";

    /** {@code (?i)Bearer\s+\S+} — case-insensitive, чтобы ловить также lowercase {@code bearer}. */
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)\\bBearer\\s+\\S+");

    /**
     * Token-параметры в query-string, form-encoded и JSON-like форматах.
     *
     * <p>Покрытые имена: {@code access_token}, {@code refresh_token}, {@code id_token},
     * {@code client_secret}, {@code assertion}, {@code client_assertion} (RFC 7521 §4.2,
     * RFC 7523 §2.2). Дефис между словами также допускается (Spring property convention).
     */
    private static final Pattern TOKEN_PARAM_PATTERN = Pattern.compile(
            "(?i)(access[-_]?token|refresh[-_]?token|id[-_]?token"
                    + "|client[-_]?secret|client[-_]?assertion|assertion)"
                    + "\\s*[=:]\\s*[^&;\\s\"']+");

    /**
     * Compact JWT serialization (RFC 7519 §3.1): три сегмента base64url, разделённые точкой.
     *
     * <p>Header стартует строго с {@code eyJ} (base64 от {@code "{"}) — это якорь,
     * существенно сужающий область false-positive (произвольные слова вроде
     * {@code "expression"} не начинаются с {@code eyJ} и не содержат точек).
     * Минимальная длина header'а — 8 символов; payload и signature — по 2 символа,
     * чтобы покрывать как реальные JWT (с длинной RSA/EC подписью), так и
     * сокращённые формы в логах/тестах.
     */
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]{2,}");

    /**
     * Применяет полный pipeline: redaction → log-injection scrub → truncate.
     *
     * @param value     исходное сообщение (может быть {@code null})
     * @param maxLength максимальная длина результата (исключая суффикс {@code [TRUNCATED]})
     * @return безопасная для логов и diagnostic-metadata строка, либо {@code null} если
     *         входное значение не имеет текста (см. {@link StringUtils#hasText})
     */
    @Nullable
    public static String sanitize(@Nullable String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String redacted = redact(value);
        String safe = LogSanitizer.sanitize(redacted, maxLength);

        if (safe.equals(LogSanitizer.NULL_MARKER)) {
            return null;
        }

        return safe;
    }

    /**
     * Только redaction-стадия (Bearer / token-params / JWT), без log-injection scrub
     * и truncate. Используется в местах, где scrub уже выполнен на следующем уровне.
     */
    @NonNull
    public static String redact(@NonNull String value) {
        String result = BEARER_PATTERN.matcher(value).replaceAll("Bearer " + REDACTED);
        result = TOKEN_PARAM_PATTERN.matcher(result).replaceAll(matchResult -> {
            String name = matchResult.group(1);
            return java.util.regex.Matcher.quoteReplacement(name + "=" + REDACTED);
        });
        return JWT_PATTERN.matcher(result).replaceAll(JWT_REDACTED);
    }
}
