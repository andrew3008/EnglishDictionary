package space.br1440.platform.tracing.otel.extension.propagation;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * ENV-aware дефолт для {@code otel.propagators}: дописывает named-пропагатор
 * {@code platform-trace-control}, если оператор его не указал и не отключил всё через {@code none}
 * (Фаза 15, PR-2).
 * <p>
 * Реализован как {@code addPropertiesCustomizer} (а <b>не</b> {@code addPropertiesSupplier}):
 * supplier имеет низший приоритет и не видит итоговый merge — если оператор задал
 * {@code otel.propagators} через ENV/sysprop, supplier-дефолт был бы проигнорирован, и
 * платформенный пропагатор не добавился бы. Customizer читает <b>уже смерженный</b>
 * {@link ConfigProperties} (включая {@code OTEL_PROPAGATORS}) и возвращает override — корректное
 * «add if absent». Эталон upstream — {@code ResourceProviderPropertiesCustomizer}.
 */
public final class PlatformPropagatorsDefaultsCustomizer
        implements Function<ConfigProperties, Map<String, String>> {

    /** Стандартный ключ списка пропагаторов OTel SDK. */
    static final String PROPAGATORS_KEY = "otel.propagators";

    /** Имя платформенного named-пропагатора. */
    static final String PLATFORM_PROPAGATOR = PlatformTraceControlPropagatorProvider.NAME;

    /** Признак полного отключения пропагации в OTel ({@code otel.propagators=none}). */
    static final String NONE = "none";

    /**
     * Дефолт OTel SDK, применяемый когда {@code otel.propagators} не задан явно
     * (SDK резолвит его внутри {@code PropagatorConfiguration}, но {@code ConfigProperties#getList}
     * для незаданного ключа вернёт пустой список — поэтому дефолт восстанавливаем здесь).
     */
    static final List<String> OTEL_DEFAULT_PROPAGATORS = List.of("tracecontext", "baggage");

    @Override
    public Map<String, String> apply(ConfigProperties config) {
        List<String> propagators = config.getList(PROPAGATORS_KEY);
        if (propagators.isEmpty()) {
            // otel.propagators не задан → действует дефолт OTel (tracecontext,baggage).
            propagators = OTEL_DEFAULT_PROPAGATORS;
        }
        // Оператор явно отключил всю пропагацию — не вмешиваемся.
        if (propagators.contains(NONE)) {
            return Map.of();
        }
        // Платформенный пропагатор уже присутствует — дубль не создаём.
        if (propagators.contains(PLATFORM_PROPAGATOR)) {
            return Map.of();
        }
        List<String> updated = new ArrayList<>(propagators);
        updated.add(PLATFORM_PROPAGATOR);
        return Map.of(PROPAGATORS_KEY, String.join(",", updated));
    }
}
