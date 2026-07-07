package space.br1440.platform.tracing.otel.extension.propagation;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

/**
 * Единый framework-agnostic builder платформенного {@link PlatformTraceControlPropagator}
 * (Фаза 15, PR-0).
 * <p>
 * Вынесен, чтобы named SPI {@code ConfigurablePropagatorProvider}
 * ({@code otel.propagators=...,platform-trace-control}) строил пропагатор из того же источника
 * истины, что и остальная конфигурация: имена платформенных заголовков берутся из
 * {@link PropagationDefaults} (с дефолтами {@code X-Trace-On}/{@code X-QA-Trace}/{@code X-Request-Id}).
 * <p>
 * Возвращаемый пропагатор обёрнут в {@link SafeTextMapPropagator} (Фаза 11, safe boundary): сбой
 * extract/inject платформенного слоя не должен ломать W3C/baggage-цепочку Агента.
 */
public final class PlatformTraceControlPropagatorBuilder {

    private PlatformTraceControlPropagatorBuilder() {
        // utility-класс
    }

    /** Строит safe-обёрнутый {@link PlatformTraceControlPropagator} из {@link ConfigProperties}. */
    public static TextMapPropagator build(ConfigProperties config) {
        PlatformTraceControlPropagator control = new PlatformTraceControlPropagator(
                PropagationDefaults.getForceTraceHeader(config),
                PropagationDefaults.getQaTraceHeader(config),
                PropagationDefaults.getRequestIdHeader(config)
        );
        return new SafeTextMapPropagator(control);
    }
}
