package space.br1440.platform.tracing.autoconfigure.servlet;

import io.opentelemetry.context.Context;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.api.propagation.control.TraceControlHeaderInjector;

import java.io.IOException;

/**
 * Client-интерсептор Servlet-стека ({@code RestTemplate}/{@code RestClient}), добавляющий
 * платформенные управляющие заголовки в исходящий запрос на доверенные хосты.
 * <p>
 * Agent-compatible: НЕ создаёт span'ы и НЕ инжектит W3C {@code traceparent}/{@code tracestate}
 * (это делает OTel Java Agent). Инжектирует только платформенные заголовки и только при
 * положительном trusted-решении (secure-by-default). Любые ошибки трассировки не должны
 * прерывать бизнес-вызов.
 */
public final class PlatformOutboundHttpInterceptor implements ClientHttpRequestInterceptor {

    private final OutboundPropagationPolicy policy;
    private final TraceControlHeaderInjector injector;

    public PlatformOutboundHttpInterceptor(OutboundPropagationPolicy policy, TraceControlHeaderInjector injector) {
        this.policy = policy;
        this.injector = injector;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        try {
            String host = request.getURI().getHost();
            OutboundPropagationDecision decision = policy.decide(host);
            Context decided = Context.current().with(PlatformTraceContextKeys.PROPAGATION_DECISION, decision);
            injector.inject(decided, request, PlatformHttpRequestSetter.INSTANCE);
        } catch (RuntimeException ignored) {
            // Изоляция: сбой propagation не должен влиять на исходящий запрос.
        }
        return execution.execute(request, body);
    }
}
