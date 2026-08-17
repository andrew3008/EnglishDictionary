package space.br1440.platform.tracing.runtime.propagation.control;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;

public interface TraceControlHeaderInjector {

    <C> void inject(Context context, C carrier, TextMapSetter<C> setter);

}
