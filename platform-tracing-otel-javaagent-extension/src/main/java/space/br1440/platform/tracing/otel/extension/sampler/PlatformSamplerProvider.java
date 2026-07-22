package space.br1440.platform.tracing.otel.extension.sampler;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionConfig;

/**
 * Named SPI платформенного head-sampler'а (Фаза 15, PR-1): активируется через
 * {@code otel.traces.sampler=platform} в agent/SDK autoconfigure-режиме.
 * <p>
 * Регистрируется в
 * {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider}.
 * SDK резолвит base-sampler по имени {@code platform}, после чего применяет
 * {@code addSamplerCustomizer}-кастомайзеры; платформенный inline-customizer распознаёт уже
 * построенный sampler по маркеру {@link PlatformManagedSampler} и не оборачивает его повторно
 * (idempotency-guard, см. {@code PlatformSamplerFactory}).
 * <p>
 * Сборка делегирована в {@link PlatformSamplerBuilder} — общий источник истины с inline-каналом.
 * JMX-привязка runtime-управления выполняется inline-customizer'ом (он владеет
 * {@code PlatformTracingJmxRegistrar}), поэтому named-провайдер JMX не трогает.
 * <p>
 * Дефолтом {@code otel.traces.sampler=platform} <b>не</b> устанавливается (named — явный opt-in):
 * сохраняется текущее «compose-over-existing» поведение ({@code ADR-sampler-compose}).
 *
 * <h2>OTel SPI exception (PR-5, аналог PR-3R ResourceProvider)</h2>
 * Этот провайдер поднимается через {@code ServiceLoader} — вне bootstrap-цепочки
 * {@link space.br1440.platform.tracing.otel.extension.PlatformAutoConfigurationCustomizer}.
 * Он сам строит {@link ExtensionConfig} из {@link ConfigProperties}, чтобы передать
 * {@code SamplingExtensionConfig} в {@link PlatformSamplerBuilder}. Это осознанное исключение:
 * вне SPI-пути используется bootstrap-экземпляр из {@code PlatformAutoConfigurationCustomizer}.
 */
public final class PlatformSamplerProvider implements ConfigurableSamplerProvider {

    /** Имя sampler'а для {@code otel.traces.sampler}. */
    public static final String NAME = "platform";

    @Override
    public Sampler createSampler(ConfigProperties config) {
        // Named SPI channel: OTel SPI exception (ServiceLoader lifecycle has no access to the
        // bootstrap ExtensionConfig). ExtensionConfig is constructed here solely to produce
        // SamplingExtensionConfig — consistent with ALIGN_TO_EXTENSION_DEFAULTS (PR-5).
        return PlatformSamplerBuilder.build(new ExtensionConfig(config).sampling());
    }

    @Override
    public String getName() {
        return NAME;
    }
}
