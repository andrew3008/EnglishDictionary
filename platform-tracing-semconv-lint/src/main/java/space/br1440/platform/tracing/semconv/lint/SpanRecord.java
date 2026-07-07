package space.br1440.platform.tracing.semconv.lint;

import java.util.Collections;
import java.util.Map;

/**
 * Минималистичная DTO одного span'а на входе линтера.
 * <p>
 * Линтер не зависит от OpenTelemetry SDK напрямую: входные данные приходят из CI-пайплайна
 * как JSON-документ, парсятся {@link space.br1440.platform.tracing.semconv.lint.cli.SpanJsonReader}
 * и приводятся к этому виду.
 *
 * @param name              имя span'а;
 * @param kind              SpanKind в строковом виде (например, {@code SERVER});
 * @param statusCode        статус: {@code OK} / {@code ERROR} / {@code UNSET};
 * @param attributes        атрибуты самого span'а (значения приведены к строкам);
 * @param resourceAttributes атрибуты {@code Resource} (service.name и т.п.).
 */
public record SpanRecord(String name,
                         String kind,
                         String statusCode,
                         Map<String, String> attributes,
                         Map<String, String> resourceAttributes) {

    public SpanRecord {
        attributes = attributes == null ? Collections.emptyMap() : Map.copyOf(attributes);
        resourceAttributes = resourceAttributes == null ? Collections.emptyMap() : Map.copyOf(resourceAttributes);
    }

    /**
     * Возвращает значение атрибута сначала из самого span'а, затем из ресурса. Это соответствует
     * семантике OpenTelemetry: атрибуты ресурса наследуются всеми span'ами процесса.
     */
    public String resolveAttribute(String key) {
        String value = attributes.get(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return resourceAttributes.get(key);
    }
}
