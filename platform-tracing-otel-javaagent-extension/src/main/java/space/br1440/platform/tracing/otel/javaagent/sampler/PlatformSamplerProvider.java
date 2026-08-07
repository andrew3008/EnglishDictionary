package space.br1440.platform.tracing.otel.javaagent.sampler;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.NonNull;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;

/**
 * Named SPI платформенного head-sampler'а: активируется через
 * {@code otel.traces.sampler=platform} в agent/SDK autoconfigure-режиме.
 * <p>
 * Регистрируется в
 * {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider}.
 * SDK резолвит base-sampler по имени {@code platform}, после чего применяет
 * {@code addSamplerCustomizer}-кастомайзеры; платформенный inline-customizer распознаёт уже
 * построенный sampler по маркеру {@link PlatformManagedSampler} и не оборачивает его повторно.
 */
public final class PlatformSamplerProvider implements ConfigurableSamplerProvider {

    public static final String NAME = "platform";

    @Override
    public Sampler createSampler(@NonNull ConfigProperties config) {
        return PlatformSamplerBuilder.build(new ExtensionConfig(config).sampling());
    }

    @Override
    public String getName() {
        return NAME;
    }
}
