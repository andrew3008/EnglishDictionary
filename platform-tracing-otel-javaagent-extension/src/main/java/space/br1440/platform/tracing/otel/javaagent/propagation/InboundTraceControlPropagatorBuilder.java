package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

/**
 * Единый framework-agnostic builder платформенного {@link InboundTraceControlPropagator}
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
public final class InboundTraceControlPropagatorBuilder {

    private InboundTraceControlPropagatorBuilder() {
        // utility-класс
    }

    /** Строит safe-обёрнутый {@link InboundTraceControlPropagator} из {@link ConfigProperties}. */
    public static TextMapPropagator build(ConfigProperties config) {
        InboundTraceControlPropagator control = new InboundTraceControlPropagator(
                PropagationDefaults.getForceTraceHeader(config),
                PropagationDefaults.getQaTraceHeader(config),
                PropagationDefaults.getRequestIdHeader(config)
        );
        return new SafeTextMapPropagator(control);
    }
}
