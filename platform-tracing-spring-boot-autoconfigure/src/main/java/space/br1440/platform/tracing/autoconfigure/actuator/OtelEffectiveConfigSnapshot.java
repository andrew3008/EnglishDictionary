package space.br1440.platform.tracing.autoconfigure.actuator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Сборщик «эффективной» конфигурации OpenTelemetry SDK, видимой через
 * {@code GET /actuator/tracing}.
 * <p>
 * Назначение — диагностика: показать оператору, какие значения свойств OTel SDK реально
 * применяются в текущем JVM, с учётом полной иерархии источников OTel autoconfigure:
 * <ol>
 *   <li>System properties ({@code -Dotel.*}) — высший приоритет;</li>
 *   <li>Environment variables ({@code OTEL_*});</li>
 *   <li>Платформенные дефолты, поставляемые extension SPI
 *       (см. {@code PlatformAutoConfigurationCustomizer.platformDefaults()});</li>
 *   <li>Жёсткие дефолты OTel SDK.</li>
 * </ol>
 * Снапшот строится без обращения к OTel-объектам (TracerProvider, ConfigProperties),
 * поскольку эндпоинт работает в classloader'е приложения, а реальные ConfigProperties
 * живут в classloader'е agent'а. Поэтому источниками для снапшота являются {@link System}
 * и {@link System#getenv()} — это то, что оператор увидит через JMX на хосте.
 *
 * <h2>Маскирование секретов</h2>
 * <p>
 * Любые свойства, содержащие {@code header}, {@code token}, {@code key}, {@code password},
 * {@code secret}, {@code authorization} в имени — маскируются перед выводом. Это защищает
 * от случайной утечки заголовков аутентификации, которые могут быть прокинуты через
 * {@code OTEL_EXPORTER_OTLP_HEADERS}.
 *
 * <h2>Порядок ключей</h2>
 * <p>
 * Ключи выводятся отсортированными лексикографически — это упрощает diff'ы и сравнение
 * конфигураций между окружениями.
 */
final class OtelEffectiveConfigSnapshot {

    /** Подстроки в имени свойства, при наличии которых значение маскируется. */
    private static final List<String> SECRET_NAME_FRAGMENTS = List.of(
            "header", "token", "key", "password", "secret", "authorization", "credential"
    );

    /**
     * Платформенные дефолты для свойств OTel SDK, поставляемые расширением
     * {@code platform-tracing-otel-extension} через {@code addPropertiesSupplier()}.
     * <p>
     * Значения продублированы здесь, чтобы actuator-эндпоинт мог показать их оператору в случае
     * {@code source=default}, не вытягивая в classpath сборки modular extension. Это намеренно
     * принятая дублированность ради соблюдения границы модулей: autoconfigure не зависит от
     * extension-классов, а синхронизация значений проверяется отдельным контрактным тестом
     * на уровне CI ({@code OtelEffectiveConfigSnapshotDefaultsContractTest} в autoconfigure-тестах).
     * <p>
     * При изменении любого значения здесь обязательно проверить
     * {@code PlatformTracingDefaultsProvider#supply()} и
     * {@code OtelEffectiveConfigSnapshotDefaultsContractTest}.
     */
    private static final Map<String, String> PLATFORM_DEFAULTS;

    static {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("otel.bsp.max.queue.size", "2048");
        defaults.put("otel.bsp.max.export.batch.size", "512");
        defaults.put("otel.bsp.export.timeout", "5000");
        defaults.put("otel.bsp.schedule.delay", "5000");
        defaults.put("otel.span.attribute.count.limit", "50");
        defaults.put("otel.span.attribute.value.length.limit", "1000");
        defaults.put("otel.span.event.count.limit", "10");
        defaults.put("otel.span.link.count.limit", "32");
        defaults.put("otel.attribute.count.limit", "16");
        PLATFORM_DEFAULTS = Map.copyOf(defaults);
    }

    /**
     * Перечень свойств OTel, которые отображаются в актуатор-снапшоте. Перечень намеренно
     * ограничен — это гарантирует стабильность контракта эндпоинта и защищает от случайного
     * раскрытия debug/internal свойств OTel.
     * <p>
     * При расширении списка достаточно дописать имя свойства в эту константу — и значение
     * автоматически попадёт в {@link #build()} с учётом маскирования по имени.
     */
    private static final List<String> EXPOSED_PROPERTIES = List.of(
            // Endpoints / protocol
            "otel.exporter.otlp.endpoint",
            "otel.exporter.otlp.protocol",
            "otel.exporter.otlp.timeout",
            "otel.exporter.otlp.headers",
            "otel.java.exporter.otlp.retry.disabled",
            // Sampler
            "otel.traces.sampler",
            "otel.traces.sampler.arg",
            // BSP
            "otel.bsp.max.queue.size",
            "otel.bsp.max.export.batch.size",
            "otel.bsp.schedule.delay",
            "otel.bsp.export.timeout",
            // Span limits
            "otel.span.attribute.count.limit",
            "otel.span.attribute.value.length.limit",
            "otel.span.event.count.limit",
            "otel.span.link.count.limit",
            "otel.attribute.count.limit",
            // Resource / service identity
            "otel.service.name",
            "otel.resource.attributes",
            // Java agent meta
            "otel.javaagent.enabled",
            "otel.javaagent.extensions"
    );

    private final Function<String, String> systemPropertyLookup;
    private final Function<String, String> envLookup;

    /**
     * Production-конструктор: использует реальные системные свойства и переменные окружения.
     */
    OtelEffectiveConfigSnapshot() {
        this(System::getProperty, System::getenv);
    }

    /**
     * Конструктор для unit-тестов: позволяет подменить источники.
     *
     * @param systemPropertyLookup функция-аналог {@link System#getProperty(String)}
     * @param envLookup функция-аналог {@link System#getenv(String)}
     */
    OtelEffectiveConfigSnapshot(Function<String, String> systemPropertyLookup,
                                Function<String, String> envLookup) {
        this.systemPropertyLookup = systemPropertyLookup;
        this.envLookup = envLookup;
    }

    /**
     * Собирает плоскую карту свойств: {@code (имя свойства → описание источника + значение)}.
     * <p>
     * Для каждого свойства из {@link #EXPOSED_PROPERTIES} сначала пробуем системное свойство,
     * затем переменную окружения. Если значение не найдено ни там, ни там — выводим
     * {@code "(default — sourced from extension SPI or OTel SDK)"}, чтобы оператор видел,
     * какие значения «эффективны по умолчанию».
     *
     * @return упорядоченная карта свойств; никогда не {@code null}
     */
    public Map<String, Map<String, Object>> build() {
        Map<String, Map<String, Object>> result = new TreeMap<>();
        for (String property : EXPOSED_PROPERTIES) {
            String systemValue = systemPropertyLookup.apply(property);
            String envName = toEnvName(property);
            String envValue = envLookup.apply(envName);

            String source;
            String rawValue;
            if (systemValue != null) {
                source = "system-property";
                rawValue = systemValue;
            } else if (envValue != null) {
                source = "env-var";
                rawValue = envValue;
            } else {
                String platformDefault = PLATFORM_DEFAULTS.get(property);
                if (platformDefault != null) {
                    // Источник «default-platform» помечает свойство, у которого есть платформенный
                    // дефолт через extension SPI. Оператор по этому source понимает, что значение
                    // не из ENV, не из -D, а из supplier'а platform-tracing-otel-extension.
                    source = "default-platform";
                    rawValue = platformDefault;
                } else {
                    // Дефолт берётся самим OTel SDK, платформенного override'а нет.
                    source = "default-otel-sdk";
                    rawValue = null;
                }
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("source", source);
            entry.put("value", rawValue == null ? null : maskIfSensitive(property, rawValue));
            entry.put("envVarName", envName);
            result.put(property, entry);
        }
        return result;
    }

    /**
     * Преобразует имя свойства OTel ({@code otel.bsp.max.queue.size}) в имя переменной окружения
     * ({@code OTEL_BSP_MAX_QUEUE_SIZE}) согласно стандартному соглашению OTel.
     */
    static String toEnvName(String otelProperty) {
        return otelProperty.replace('.', '_').toUpperCase(Locale.ROOT);
    }

    /**
     * Маскирует значение, если в имени свойства встречается одна из чувствительных подстрок.
     * Логика идентична подходу Spring Boot Actuator's {@code SanitizableData}, но с
     * платформенным набором префиксов.
     */
    static String maskIfSensitive(String property, String value) {
        String lower = property.toLowerCase(Locale.ROOT);
        for (String fragment : SECRET_NAME_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return "***";
            }
        }
        return value;
    }
}
