package space.br1440.platform.tracing.autoconfigure.servlet;

import java.io.IOException;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;

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
    private final PlatformOutboundPropagation propagation;

    public PlatformOutboundHttpInterceptor(OutboundPropagationPolicy policy,
                                           PlatformOutboundPropagation propagation) {
        this.policy = policy;
        this.propagation = propagation;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        try {
            String host = request.getURI().getHost();
            OutboundPropagationDecision decision = policy.decide(host);
            apply(request, propagation.resolve(decision));
        } catch (RuntimeException ignored) {
            // Изоляция: сбой propagation не должен влиять на исходящий запрос.
        }
        return execution.execute(request, body);
    }

    private static void apply(HttpRequest request, OutboundPropagationHeaders headers) {
        headers.forceTrace().ifPresent(header -> request.getHeaders().set(header.name(), header.value()));
        headers.qaTrace().ifPresent(header -> request.getHeaders().set(header.name(), header.value()));
        headers.requestId().ifPresent(header -> request.getHeaders().set(header.name(), header.value()));
    }
}
