package space.br1440.platform.hbrouting.http;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import space.br1440.platform.hbrouting.context.MeshHeaders;
import space.br1440.platform.hbrouting.context.RoutingContextHolder;

public class RoutingWebClientFilter implements ExchangeFilterFunction {

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        String branch = RoutingContextHolder.get();

        if (branch != null && !branch.isBlank()) {
            ClientRequest mutated = ClientRequest.from(request)
                    .headers(headers -> headers.set(MeshHeaders.FEATURE_BRANCH, branch))
                    .build();

            return next.exchange(mutated);
        }

        return next.exchange(request);
    }
}