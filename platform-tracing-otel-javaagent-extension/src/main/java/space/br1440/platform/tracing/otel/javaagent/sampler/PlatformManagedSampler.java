package space.br1440.platform.tracing.otel.javaagent.sampler;

/**
 * Маркер платформенного сэмплера (Фаза 15).
 * <p>
 * Используется idempotency-guard'ом в {@code addSamplerCustomizer}: когда базовый sampler уже
 * построен платформой (например, через named SPI {@code otel.traces.sampler=platform}), inline-
 * customizer не должен оборачивать его повторно. Маркер — stateless-признак, корректный при
 * многократной сборке SDK в одном JVM (в отличие от статического флага «уже применён»).
 * <p>
 * Контракт реализуют {@link CompositeSampler} (возвращает себя) и {@link SafeSampler}
 * (разворачивает делегат). Метод {@link #platformCompositeSampler()} даёт доступ к внутреннему
 * {@link CompositeSampler} для перепривязки JMX-управления к фактически работающему сэмплеру
 * без повторной композиции.
 */
public interface PlatformManagedSampler {

    /**
     * Возвращает платформенный {@link CompositeSampler} из цепочки сэмплера либо {@code null},
     * если композит недоступен (например, делегат — не платформенный sampler).
     */
    CompositeSampler platformCompositeSampler();
}
