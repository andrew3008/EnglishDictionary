package space.br1440.platform.tracing.autoconfigure.support;

import org.springframework.core.env.Environment;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

/**
 * Единая точка разрешения эффективного значения
 * {@code platform.tracing.suppression.suppress-micrometer-tracing}.
 * <p>
 * Свойство больше не требует явной настройки в типовом случае: если оператор не задал его
 * явно, значение автоматически выводится из присутствия OpenTelemetry Java Agent в текущей JVM
 * ({@link OtelAgentDetector}) — Agent создаёт HTTP-span'ы самостоятельно, поэтому Micrometer
 * Observation для тех же span'ов должен быть подавлен, и наоборот. Явно заданное значение всегда
 * имеет приоритет над auto-derive — это осознанный escape hatch для нетиповых сценариев (тесты,
 * гибридные конфигурации), а не «умное» игнорирование конфигурации оператора.
 * <p>
 * Используется как из {@code @Conditional}-классов (до создания бина {@link TracingProperties}),
 * так и из бинов, которым нужно эффективное значение — оба читают один и тот же {@link Environment},
 * что исключает расхождение между условием активации авто-конфигурации и диагностикой.
 */
public final class MicrometerTracingSuppressionResolver {

    public static final String PROPERTY_NAME =
            TracingProperties.PREFIX + ".suppression.suppress-micrometer-tracing";

    private MicrometerTracingSuppressionResolver() {
    }

    /**
     * @return явное значение свойства, если оператор его задал; иначе — присутствие
     *     OpenTelemetry Java Agent в текущей JVM ({@link OtelAgentDetector#isAgentPresent()}).
     */
    public static boolean resolve(Environment environment) {
        Boolean explicit = environment.getProperty(PROPERTY_NAME, Boolean.class);
        return explicit != null ? explicit : OtelAgentDetector.isAgentPresent();
    }

    /**
     * @return {@code true}, если оператор явно задал свойство (переопределение auto-derive).
     */
    public static boolean isExplicitlySet(Environment environment) {
        return environment.getProperty(PROPERTY_NAME, Boolean.class) != null;
    }
}
