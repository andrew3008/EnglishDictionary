package space.br1440.platform.tracing.otel.javaagent.resource;

import space.br1440.platform.tracing.otel.javaagent.utils.Strings;

import java.util.Locale;

/**
 * Нормализация значения окружения к well-known значениям OTel semconv для
 * {@code deployment.environment.name}: {@code development | staging | production | test}.
 * <p>
 * Цель — единообразие значений env между сервисами (иначе backend'ы вроде SigNoz «сливают»
 * сервисы разных сред). Распространённые алиасы приводятся к каноническим значениям; неизвестное
 * значение возвращается без изменений (passthrough) — насильно в {@code unknown} не превращаем.
 *
 * <p>Класс не инстанцируется: единственная операция — статический {@link #normalize(String, boolean)}.
 */
public final class EnvironmentNormalizer {

    /** Значение по умолчанию, когда окружение не задано. */
    public static final String UNKNOWN = "unknown";

    private EnvironmentNormalizer() {
        // utility-класс
    }

    /**
     * Нормализует значение окружения.
     *
     * @param raw     сырое значение (может быть {@code null}/пустым)
     * @param enabled включена ли нормализация алиасов; если {@code false} — значение приводится
     *                только к нижнему регистру/trim без подмены алиасов
     * @return каноническое well-known значение, либо исходное (lower-case) при неизвестном вводе,
     *         либо {@link #UNKNOWN} для пустого ввода
     */
    public static String normalize(String raw, boolean enabled) {
        if (Strings.isBlank(raw)) {
            return UNKNOWN;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!enabled) {
            return value;
        }
        switch (value) {
            case "prod":
            case "prd":
            case "production":
                return "production";
            case "stage":
            case "stg":
            case "staging":
                return "staging";
            case "dev":
            case "develop":
            case "development":
                return "development";
            case "test":
                return "test";
            default:
                // qa, qa1, qa-2 и т.п. — это тестовые контуры по платформенной QA-политике.
                if (value.startsWith("qa")) {
                    return "test";
                }
                // Неизвестное кастомное значение — passthrough (не насилуем в unknown).
                return value;
        }
    }
}
