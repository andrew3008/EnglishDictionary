package space.br1440.platform.tracing.otel.propagation.control;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * Внутренний OTel-контракт agent-side инжекции платформенных управляющих заголовков.
 */
public interface TraceControlHeaderInjector {

    <C> void inject(Context context, C carrier, TextMapSetter<C> setter);
}
