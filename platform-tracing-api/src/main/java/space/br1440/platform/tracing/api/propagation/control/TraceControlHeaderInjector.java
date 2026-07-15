package space.br1440.platform.tracing.api.propagation.control;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * Единый инжектор платформенных управляющих заголовков на исходящем вызове.
 * <p>
 * Источник истины для outbound-инжекции: и {@code InboundTraceControlPropagator.inject()}
 * (агент-сторона), и client-интерсепторы (Spring-бины) используют одну реализацию
 * ({@code DefaultTraceControlHeaderInjector} в {@code platform-tracing-core}).
 *
 * <h3>Контракт</h3>
 * <ul>
 *   <li><b>Secure-by-default:</b> если в {@link Context} нет {@link OutboundPropagationDecision},
 *       наружу НЕ уходит ничего.</li>
 *   <li>Инжектируются только три платформенных заголовка; W3C {@code traceparent}/{@code tracestate}
 *       и {@code baggage} — зона OTel Java Agent.</li>
 *   <li>Thread-safe; реализации обязаны быть stateless.</li>
 * </ul>
 *
 * @implNote {@code X-Trace-On} по умолчанию наружу не пробрасывается ({@code propagateForceTrace=false});
 *           решение о записи уже несёт sampled-flag в {@code traceparent}. Подробнее — см.
 *           {@link space.br1440.platform.tracing.core.propagation.control.DefaultTraceControlHeaderInjector}.
 * @see space.br1440.platform.tracing.core.propagation.control.DefaultTraceControlHeaderInjector
 */
public interface TraceControlHeaderInjector {

    /**
     * Инжектирует платформенные заголовки в carrier согласно решению в Context.
     * Если в {@code context} отсутствует {@link OutboundPropagationDecision}, метод является no-op.
     */
    <C> void inject(Context context, C carrier, TextMapSetter<C> setter);
}
