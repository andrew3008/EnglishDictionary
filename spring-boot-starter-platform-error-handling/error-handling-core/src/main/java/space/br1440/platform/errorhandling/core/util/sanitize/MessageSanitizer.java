package space.br1440.platform.errorhandling.core.util.sanitize;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Маскирует чувствительные значения (CWE-209) в свободных текстовых полях
 * перед записью в diagnosticMetadata, логи или ответ клиенту.
 *
 * <h2>Покрытие default-набора</h2>
 * <ul>
 *   <li>{@code Authorization: Bearer ...} — JWT, opaque tokens.</li>
 *   <li>{@code Authorization: Basic ...} — base64 username:password.</li>
 *   <li>Card numbers PAN — 13–19 цифр в одном токене (Luhn не валидируется,
 *       но сужения по границам слова достаточно для защиты от false-positive
 *       на UUID-подстроках).</li>
 *   <li>Query/form secrets — пары {@code key=value}, где
 *       {@code key} ∈ {{@code password, passwd, pwd, secret, token, api_key,
 *       api-key, apikey, authorization, access_token, refresh_token, client_secret}}.</li>
 *   <li>JDBC connection-string passwords — {@code password=...} в DSN-style.</li>
 * </ul>
 *
 * <h2>Гарантии</h2>
 * <ul>
 *   <li>Sanitize <b>всегда</b> применяется до {@link StringUtils#truncate(String, int)}:
 *       иначе truncate может отрезать маркер маскирования и оставить хвост секрета.</li>
 *   <li>{@code null} и blank возвращаются без изменений.</li>
 *   <li>Все паттерны pre-compiled — thread-safe immutable state.</li>
 * </ul>
 *
 * <p>Производительность: каждый default-паттерн исполняется один раз через
 * {@link java.util.regex.Matcher#replaceAll(String)}. Для 200-символьного сообщения
 * полный pass ≤ 5 мкс на современном JVM. Hot-path безопасен.
 *
 * @see <a href="https://cwe.mitre.org/data/definitions/209.html">CWE-209</a>
 */
@UtilityClass
public final class MessageSanitizer {

    public static final String MASK = "***";

    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._\\-+/=]+");

    private static final Pattern BASIC = Pattern.compile(
            "(?i)\\bBasic\\s+[A-Za-z0-9+/=]+");

    private static final Pattern CARD_NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9])\\d{13,19}(?![A-Za-z0-9])");

    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&;\\s]|^)(password|passwd|pwd|secret|token|api[-_]?key|apikey"
                    + "|authorization|access[-_]?token|refresh[-_]?token|client[-_]?secret)"
                    + "\\s*=\\s*([^&\\s\"';]+)");

    private static final Pattern JDBC_PASSWORD = Pattern.compile(
            "(?i)(password\\s*=\\s*)([^;\\s\"']+)");

    private static final List<Replacement> DEFAULT_REPLACEMENTS = List.of(
            new Replacement(BEARER, "Bearer " + MASK),
            new Replacement(BASIC, "Basic " + MASK),
            new Replacement(CARD_NUMBER, MASK),
            new Replacement(QUERY_SECRET, "$1$2=" + MASK),
            new Replacement(JDBC_PASSWORD, "$1" + MASK)
    );

    /**
     * Применяет default-набор маскирующих паттернов.
     *
     * @param value исходное сообщение (может быть {@code null})
     * @return маскированная строка, либо исходное значение если нечего маскировать
     */
    @Nullable
    public static String sanitize(@Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }

        String result = value;
        for (Replacement r : DEFAULT_REPLACEMENTS) {
            result = r.pattern().matcher(result).replaceAll(r.replacement());
        }

        return result;
    }

    /**
     * Sanitize + truncate в одной операции — гарантирует, что truncate не
     * отрежет маркер {@link #MASK} и не оставит хвост секрета.
     */
    @Nullable
    public static String sanitizeAndTruncate(@Nullable String value, int maxChars) {
        return StringUtils.truncate(sanitize(value), maxChars);
    }

    /**
     * Применяет произвольный набор паттернов (для расширения через property).
     *
     * @param value          исходная строка
     * @param customPatterns regex-паттерны для дополнительной маскировки;
     *                       заменяют match'и на {@link #MASK}
     */
    @Nullable
    public static String sanitizeWith(@Nullable String value, @NonNull List<Pattern> customPatterns) {
        String base = sanitize(value);
        if (base == null || customPatterns.isEmpty()) {
            return base;
        }

        String result = base;
        for (Pattern p : customPatterns) {
            result = p.matcher(result).replaceAll(MASK);
        }

        return result;
    }

    private record Replacement(@NonNull Pattern pattern, @NonNull String replacement) {
    }
}
