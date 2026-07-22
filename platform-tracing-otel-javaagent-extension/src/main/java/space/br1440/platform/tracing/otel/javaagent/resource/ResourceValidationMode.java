package space.br1440.platform.tracing.otel.javaagent.resource;

import space.br1440.platform.tracing.otel.javaagent.utils.Strings;

import java.util.Locale;

/**
 * Режим валидации resource-идентичности сервиса на старте.
 * <p>
 * Две опции — консистентно со стилем {@code ScrubbingRulesLoader} (LENIENT|STRICT) и с
 * {@code platform.tracing.validation.strict}. Третий режим (WARN) сознательно не вводится,
 * чтобы не разрастать API.
 *
 * <ul>
 *   <li>{@link #LENIENT} — нарушения только логируются (WARN), приложение стартует;</li>
 *   <li>{@link #STRICT} — при отсутствии required-ключей выбрасывается {@code IllegalStateException}
 *       (fail-fast: процесс не поднимается с битой идентичностью).</li>
 * </ul>
 */
public enum ResourceValidationMode {

    LENIENT,
    STRICT;

    /**
     * Разбирает значение конфигурации. Неизвестное/пустое значение трактуется как {@link #LENIENT}
     * (prod-safe, не-ломающий дефолт).
     *
     * @param raw сырое значение свойства (может быть {@code null})
     * @return распознанный режим либо {@link #LENIENT}
     */
    public static ResourceValidationMode fromConfig(String raw) {
        if (Strings.isBlank(raw)) {
            return LENIENT;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (STRICT.name().equals(normalized)) {
            return STRICT;
        }
        return LENIENT;
    }
}
