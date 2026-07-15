package space.br1440.platform.tracing.otel.extension.propagation;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor;
import space.br1440.platform.tracing.api.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.api.propagation.control.TraceControlHeaderInjector;
import space.br1440.platform.tracing.core.propagation.control.DefaultInboundTraceControlExtractor;
import space.br1440.platform.tracing.core.propagation.control.DefaultTraceControlHeaderInjector;
import space.br1440.platform.tracing.otel.extension.utils.Strings;

import java.util.Collection;
import java.util.List;

/**
 * Извлекает платформенные управляющие заголовки из carrier'а и сохраняет их в Context
 * для использования при принятии решения о сэмплировании.
 * На исходящих вызовах (inject) делегирует в {@link TraceControlHeaderInjector}: заголовки
 * передаются только при наличии решения о propagation в Context (secure-by-default).
 */
public final class InboundTraceControlPropagator implements TextMapPropagator {

    private final String forceTraceHeader;
    private final String qaTraceHeader;
    private final String requestIdHeader;

    private final Collection<String> fields;

    // Единый источник истины для outbound-инжекции (общий с client-интерсепторами через platform-tracing-api).
    private final TraceControlHeaderInjector outboundInjector;
    private final InboundTraceControlExtractor inboundExtractor;

    // Runtime kill-switch платформенной пропагации (Фаза 14). По умолчанию — процессный shared-гейт.
    private final PlatformPropagationGate gate;

    public InboundTraceControlPropagator(String forceTraceHeader, String qaTraceHeader, String requestIdHeader) {
        this(forceTraceHeader, qaTraceHeader, requestIdHeader, PlatformPropagationGate.shared());
    }

    InboundTraceControlPropagator(String forceTraceHeader, String qaTraceHeader, String requestIdHeader,
                                   PlatformPropagationGate gate) {
        this.forceTraceHeader = forceTraceHeader;
        this.qaTraceHeader = qaTraceHeader;
        this.requestIdHeader = requestIdHeader;
        this.fields = List.of(forceTraceHeader, qaTraceHeader, requestIdHeader);
        this.outboundInjector = new DefaultTraceControlHeaderInjector(forceTraceHeader, qaTraceHeader, requestIdHeader);
        this.inboundExtractor = new DefaultInboundTraceControlExtractor();
        this.gate = gate;
    }

    @Override
    public Collection<String> fields() {
        return fields;
    }

    @Override
    public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
        if (context == null || getter == null) {
            return context;
        }

        // Платформенная пропагация выключена в рантайме: не извлекаем управляющие заголовки.
        // W3C-контекст не трогается (его обрабатывает Агент) — trace-context продолжает ходить.
        if (!gate.isEnabled()) {
            gate.recordGatedExtract();
            return context;
        }

        String forceTraceVal = getter.get(carrier, forceTraceHeader);
        String qaTraceVal = getter.get(carrier, qaTraceHeader);
        String requestIdVal = getter.get(carrier, requestIdHeader);
        // Если ни один из заголовков не передан, не меняем контекст
        if (isEmpty(forceTraceVal) && isEmpty(qaTraceVal) && isEmpty(requestIdVal)) {
            return context;
        }

        InboundTraceControl control = inboundExtractor.fromHeaders(forceTraceVal, qaTraceVal, requestIdVal);

        return context.with(PlatformTraceContextKeys.TRACE_CONTROL, control);
    }

    @Override
    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
        // Платформенная пропагация выключена в рантайме: наружу не уходят управляющие заголовки.
        // W3C traceparent/tracestate инжектирует Агент отдельным пропагатором — он не затрагивается.
        if (!gate.isEnabled()) {
            gate.recordGatedInject();
            return;
        }
        // Делегируем единому инжектору: secure-by-default (без PROPAGATION_DECISION ничего не уходит),
        // только платформенные заголовки, W3C/baggage не трогаем (idempotent с Агентом).
        outboundInjector.inject(context, carrier, setter);
    }

    private static boolean isEmpty(String val) {
        return Strings.isBlank(val);
    }
}
