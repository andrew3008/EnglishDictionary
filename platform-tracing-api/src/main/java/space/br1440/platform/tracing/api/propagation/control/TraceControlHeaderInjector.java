package space.br1440.platform.tracing.api.propagation.control;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * Единый инжектор платформенных управляющих заголовков на исходящем вызове.
 * <p>
 * Secure-by-default: если в {@link Context} нет {@link OutboundPropagationDecision},
 * наружу НЕ уходит ничего. Инжектируются только три платформенных заголовка;
 * W3C {@code traceparent}/{@code tracestate} и {@code baggage} — зона OTel Java Agent.
 * <p>
 * Реализация: {@code space.br1440.platform.tracing.core.propagation.control.DefaultTraceControlHeaderInjector}.
 */
public interface TraceControlHeaderInjector {

    /**
     * Инжектирует платформенные заголовки в carrier согласно решению в Context.
     *
     * @param context OTel контекст, содержащий {@link OutboundPropagationDecision} и {@link InboundTraceControl}
     * @param carrier носитель заголовков (HTTP-запрос, Kafka ProducerRecord headers и т.д.)
     * @param setter  стратегия записи заголовков в carrier
     */
    <C> void inject(Context context, C carrier, TextMapSetter<C> setter);
}
