package space.br1440.platform.tracing.otel.extension.sampler;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import space.br1440.platform.tracing.otel.extension.configuration.SamplingExtensionConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Единый framework-agnostic builder платформенного head-sampler'а (Фаза 15, PR-0).
 * <p>
 * Вынесен из {@code PlatformSamplerFactory}, чтобы оба канала бутстрапа агента строили
 * <b>идентичный</b> sampler из одного источника истины:
 * <ul>
 *   <li>inline {@code addSamplerCustomizer} (default, compose-over-existing);</li>
 *   <li>named SPI {@code ConfigurableSamplerProvider} ({@code otel.traces.sampler=platform}).</li>
 * </ul>
 * <p>
 * PR-5: builder принимает {@link SamplingExtensionConfig} — immutable startup-снимок,
 * построенный bootstrap-владельцем ({@code PlatformAutoConfigurationCustomizer}).
 * Прямое чтение {@code ConfigProperties} / {@code ExtensionPropertyNames} / {@code ExtensionDefaults}
 * намеренно удалено; {@code ALIGN_TO_EXTENSION_DEFAULTS = 0.1} применяется через дефолт
 * {@link SamplingExtensionConfig#ratio()}.
 * <p>
 * JMX-регистрация в этот класс <b>намеренно не входит</b> (без side-effect'ов): её выполняет
 * вызывающая фабрика, которая владеет {@code PlatformTracingJmxRegistrar}. Это позволяет
 * переиспользовать builder из ServiceLoader-провайдера, не имеющего ссылки на registrar.
 */
public final class PlatformSamplerBuilder {

    private PlatformSamplerBuilder() {
        // utility-класс
    }

    /**
     * Строит платформенный sampler из стартовой конфигурации.
     * <p>
     * Возвращаемый {@link SafeSampler} реализует {@link PlatformManagedSampler}: вызывающий код
     * может извлечь внутренний {@link CompositeSampler} (и его {@link SamplerStateHolder}) для
     * привязки JMX-управления через {@link PlatformManagedSamplers#findComposite(Sampler)}.
     *
     * @param sampling immutable startup sampling config; ratio defaults to
     *                 {@code ExtensionDefaults.DEFAULT_SAMPLING_RATIO = 0.1} when property is
     *                 absent or blank (ALIGN_TO_EXTENSION_DEFAULTS, PR-5)
     */
    public static Sampler build(SamplingExtensionConfig sampling) {
        double ratio = sampling.ratio();
        boolean enabled = sampling.enabled();
        Map<String, Double> routeRatios = parseRouteRatios(sampling.routeRatios());

        // Startup-init (Фаза 14): enabled/ratio/dropPaths/forceValues/routeRatios seedируют
        // SamplerStateHolder, чтобы окно до первого JMX-пуша Spring работало с верной политикой.
        SamplerStateHolder configHolder = new SamplerStateHolder(
                enabled, sampling.dropPaths(), sampling.forceRecordValues(), routeRatios, ratio);
        CompositeSampler compositeSampler = new CompositeSampler(configHolder);

        // Safe-обёртка (Фаза 11): изоляция hot-path shouldSample от падений кастомных правил.
        // Fallback — консервативный parentBased(traceIdRatioBased(ratio)).
        Sampler fallback = Sampler.parentBased(Sampler.traceIdRatioBased(ratio));
        return new SafeSampler(compositeSampler, fallback);
    }

    /**
     * Парсит стартовые route-ratios из {@code SamplingExtensionConfig#routeRatios} (значения — строки).
     * Невалидные/непарсящиеся значения пропускаются; валидация диапазона {@code defaultRatio}
     * выполняется в {@link space.br1440.platform.tracing.core.sampling.properties.SamplingPolicySnapshotFactory}
     * через {@link space.br1440.platform.tracing.core.sampling.properties.SamplingPolicyPropertiesValidator}
     * при построении {@link SamplerState}.
     */
    static Map<String, Double> parseRouteRatios(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> parsed = new HashMap<>(raw.size());
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            try {
                parsed.put(entry.getKey(), Double.parseDouble(entry.getValue().trim()));
            } catch (NumberFormatException ignored) {
                // невалидное значение route-ratio в startup-конфиге — пропускаем
            }
        }
        return parsed;
    }
}
