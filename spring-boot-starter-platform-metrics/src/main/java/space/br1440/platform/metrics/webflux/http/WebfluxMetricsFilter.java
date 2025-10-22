package space.br1440.platform.metrics.webflux.http;

import io.micrometer.core.instrument.Timer;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import space.br1440.platform.metrics.MetricBuilder;


public class WebfluxMetricsFilter implements WebFilter, Ordered {

    private final MetricBuilder metricBuilder;

    public WebfluxMetricsFilter(MetricBuilder metricBuilder) {
        this.metricBuilder = metricBuilder;
    }


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var sample = Timer.start();
        return chain.filter(exchange)
                .doOnSuccess(aVoid -> endTimerRespectingCommit(exchange, sample))
                .doOnError(throwable -> endTimerRespectingCommit(exchange, sample));

    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }

    private void endTimerRespectingCommit(ServerWebExchange exchange, Timer.Sample sample) {

        var response = exchange.getResponse();
        var request = exchange.getRequest();
        if (response.isCommitted()) {
            endTimerInner(exchange, sample);
        } else {
            response.beforeCommit(() -> {
                endTimerInner(exchange, sample);
                return Mono.empty();
            });
        }
        metricBuilder.httpResponseCounter(
                request.getMethod().name(),
                request.getPath().toString(),
                String.valueOf(response.getStatusCode().value())
        ).increment();
    }

    private void endTimerInner(ServerWebExchange exchange, Timer.Sample sample) {
        var response = exchange.getResponse();
        var request = exchange.getRequest();

        sample.stop(metricBuilder.httpTimer(
                request.getMethod().name(),
                request.getPath().toString(),
                String.valueOf(response.getStatusCode().value()))
        );
    }
}