package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider;

/**
 * Named SPI платформенного управляющего пропагатора (Фаза 15, PR-2): делает
 * {@link InboundTraceControlPropagator} discoverable через
 * {@code otel.propagators=...,platform-trace-control} в agent/SDK autoconfigure-режиме.
 * <p>
 * Регистрируется в
 * {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider}.
 * Дефолт автоматически дописывает {@value #NAME} в {@code otel.propagators}, если оператор его не
 * указал и не отключил всё через {@code none} (см. {@code PlatformAutoConfigurationCustomizer}
 * через {@code addPropertiesCustomizer}).
 * <p>
 * Пропагатор строго <b>дополняет</b> W3C {@code tracecontext}/{@code baggage} (зона Агента), а не
 * заменяет их ({@code ADR-context-first-propagation}).
 */
public final class InboundTraceControlPropagatorProvider implements ConfigurablePropagatorProvider {

    /** Имя пропагатора для {@code otel.propagators}. */
    public static final String NAME = "platform-trace-control";

    @Override
    public TextMapPropagator getPropagator(ConfigProperties config) {
        return InboundTraceControlPropagatorBuilder.build(config);
    }

    @Override
    public String getName() {
        return NAME;
    }
}
