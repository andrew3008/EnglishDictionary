package space.br1440.platform.errorhandling.core.util.sanitize;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Заменяет динамические сегменты URL-пути на placeholder {@code {id}}
 * для безопасного включения путей в diagnosticMetadata и метрики.
 *
 * <h2>Покрываемые форматы</h2>
 * <ul>
 *   <li>Числовые ID любого письменного направления — regex {@code \p{N}+}.
 *       Покрывает арабские, восточно-арабские, кириллические-fullwidth,
 *       китайские числительные, бенгальские и т.д. {@code [0-9]+} тут
 *       недостаточно (CWE-209: атакующий может закодировать ID в polyglot
 *       представлении и обойти sanitizer).</li>
 *   <li>UUID — каноничный 8-4-4-4-12 hex-формат, регистронезависимо.</li>
 *   <li>Hex-токены ≥ 6 символов — session IDs, hashes, opaque tokens.</li>
 *   <li>Multi-сегментные комбинации: {@code /users/42/orders/abc-uuid}
 *       → {@code /users/{id}/orders/{id}}.</li>
 * </ul>
 *
 * <h2>Что не санитизируется</h2>
 * <ul>
 *   <li>Имена ресурсов из букв ({@code /users}, {@code /v1/orders}) — структура.</li>
 *   <li>Hex короче 6 символов — слишком высок риск false-positive
 *       на коротких бизнес-кодах (например, {@code /api/eu/users}).</li>
 *   <li>Query string — обрезается целиком (см. {@link #sanitize(String)});
 *       query содержит secrets, parsed-only-after-sanitize.</li>
 * </ul>
 *
 * <h2>Limit length</h2>
 * <p>Финальный путь {@link StringUtils#truncate(String, int) truncate} до
 * {@value #MAX_PATH_LENGTH} символов — защита от unbounded path в логах
 * и кардинальности метрик.
 *
 * <p>Thread-safe: pre-compiled patterns, без shared state.
 */
@UtilityClass
public final class PathSanitizer {

    public static final int MAX_PATH_LENGTH = 120;

    public static final String PLACEHOLDER = "{id}";

    private static final Pattern QUERY_OR_FRAGMENT = Pattern.compile("[?#].*$");

    /**
     * Один регэксп ловит все три класса динамики, в порядке убывания
     * специфичности (UUID → hex ≥ 6 → numeric с поддержкой polyglot).
     * Привязка к границам сегмента ({@code /} или начало/конец строки)
     * предотвращает мисматчи внутри статических сегментов вроде
     * {@code /v1/orders} (где {@code 1} — часть имени версии).
     */
    private static final Pattern DYNAMIC_SEGMENT = Pattern.compile("""
        (?<=/|^)(?:
        [0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}
        |[0-9a-fA-F]{6,}
        |\\p{N}+
        )(?=/|$)""", Pattern.COMMENTS
    );

    /**
     * Sanitize + truncate в одной операции.
     *
     * @param path исходный путь (может быть {@code null} или blank)
     * @return санитизированный и обрезанный путь, либо {@code null}/blank как был
     */
    public static String sanitize(@Nullable String path) {
        return sanitize(path, MAX_PATH_LENGTH);
    }

    /**
     * Sanitize с явным {@code maxLength} (для тестов и кастомных лимитов).
     */
    @NonNull
    public static String sanitize(String path, int maxLength) {
        if (!StringUtils.hasText(path)) {
            return path;
        }

        String withoutQuery = QUERY_OR_FRAGMENT.matcher(path).replaceAll("");
        String sanitized = DYNAMIC_SEGMENT.matcher(withoutQuery).replaceAll(PLACEHOLDER);

        return Objects.requireNonNull(StringUtils.truncate(sanitized, maxLength));
    }
}
