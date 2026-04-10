package space.br1440.platform.metrics.webflux.http;

import io.micrometer.core.instrument.Timer;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import space.br1440.platform.metrics.MetricBuilder;
import space.br1440.platform.metrics.config.HttpMetricProperties;

public class WebfluxMetricsFilter implements WebFilter, Ordered {

    private static final String UNKNOWN_PATH = "UNKNOWN";
    private static final String NOT_FOUND_PATH = "NOT_FOUND";
    private static final String REDIRECTION_PATH = "REDIRECTION";
    private static final String ROOT_PATH = "root";
    private static final String UNKNOWN_METHOD = "UNKNOWN";
    private static final String UNKNOWN_STATUS = "UNKNOWN";

    private final MetricBuilder metricBuilder;
    private final HttpMetricProperties httpMetricProperties;

    public WebfluxMetricsFilter(MetricBuilder metricBuilder,
                                HttpMetricProperties httpMetricProperties) {
        this.metricBuilder = metricBuilder;
        this.httpMetricProperties = httpMetricProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Timer.Sample sample = Timer.start();

        return chain.filter(exchange)
                .doOnSuccess(ignored -> finishMetrics(exchange, sample))
                .doOnError(ignored -> finishMetrics(exchange, sample));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private void finishMetrics(ServerWebExchange exchange, Timer.Sample sample) {
        if (exchange.getResponse().isCommitted()) {
            recordMetrics(exchange, sample);
            return;
        }

        exchange.getResponse().beforeCommit(() -> {
            recordMetrics(exchange, sample);
            return Mono.empty();
        });
    }

    private void recordMetrics(ServerWebExchange exchange, Timer.Sample sample) {
        String method = resolveMethod(exchange);
        String status = resolveStatus(exchange);
        String path = resolvePath(exchange);

        sample.stop(metricBuilder.httpTimer(method, path, status));
        metricBuilder.httpResponseCounter(method, path, status).increment();
    }

    private String resolveMethod(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() != null
                ? exchange.getRequest().getMethod().name()
                : UNKNOWN_METHOD;
    }

    private String resolveStatus(ServerWebExchange exchange) {
        return exchange.getResponse().getStatusCode() != null
                ? String.valueOf(exchange.getResponse().getStatusCode().value())
                : UNKNOWN_STATUS;
    }

    private String resolvePath(ServerWebExchange exchange) {
        if (httpMetricProperties.getPathMode() == HttpMetricProperties.PathMode.RAW) {
            String rawPath = exchange.getRequest().getPath().toString();
            return (rawPath == null || rawPath.isBlank()) ? UNKNOWN_PATH : rawPath;
        }

        if (exchange.getResponse().getStatusCode() != null) {
            int status = exchange.getResponse().getStatusCode().value();

            if (status >= 300 && status < 400) {
                return REDIRECTION_PATH;
            }
            if (status == 404) {
                return NOT_FOUND_PATH;
            }
        }

        Object bestMatchingPattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (bestMatchingPattern != null) {
            return bestMatchingPattern.toString();
        }

        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return ROOT_PATH;
        }

        return UNKNOWN_PATH;
    }
}