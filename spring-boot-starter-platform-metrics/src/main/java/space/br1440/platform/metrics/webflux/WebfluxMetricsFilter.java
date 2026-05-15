package space.br1440.platform.metrics.webflux;

import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.configuration.properties.HttpMetricProperties;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class WebfluxMetricsFilter implements WebFilter, Ordered {

    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    private static final String UNKNOWN_PATH = "UNKNOWN";
    private static final String NOT_FOUND_PATH = "NOT_FOUND";
    private static final String REDIRECTION_PATH = "REDIRECTION";

    private static final String ROOT_PATH = "ROOT";
    private static final String UNKNOWN_STATUS = "UNKNOWN";

    private final PlatformMetrics platformMetrics;
    private final HttpMetricProperties httpMetricProperties;

    public WebfluxMetricsFilter(PlatformMetrics platformMetrics,
                                HttpMetricProperties httpMetricProperties) {
        this.platformMetrics = platformMetrics;
        this.httpMetricProperties = httpMetricProperties;
    }

    @PostConstruct
    void warnIfRawMode() {
        if (httpMetricProperties.getPathMode() == HttpMetricProperties.PathMode.RAW) {
            log.warn("""
                WebfluxMetricsFilter: PathMode.RAW is enabled. \
                This may cause cardinality explosion in metrics. \
                Consider using PathMode.TEMPLATE (default).\
            """);
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Timer.Sample sample = Timer.start();
        AtomicBoolean recorded = new AtomicBoolean(false);
        return chain.filter(exchange)
                .doFinally(signalType -> finishMetrics(exchange, sample, recorded, signalType));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private void finishMetrics(ServerWebExchange exchange, Timer.Sample sample,
                               AtomicBoolean recorded, SignalType signalType) {
        if ((signalType == SignalType.CANCEL) || exchange.getResponse().isCommitted()) {
            recordMetrics(exchange, sample, recorded);
            return;
        }

        exchange.getResponse().beforeCommit(() -> {
            recordMetrics(exchange, sample, recorded);
            return Mono.empty();
        });
    }

    private void recordMetrics(ServerWebExchange exchange, Timer.Sample sample, AtomicBoolean recorded) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }

        String method = resolveMethod(exchange);
        String status = resolveStatus(exchange);
        String path = resolvePath(exchange);
        sample.stop(platformMetrics.httpTimer(method, path, status));
        platformMetrics.httpResponseCounter(method, path, status).increment();
    }

    private String resolveMethod(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod().name();
    }

    private String resolveStatus(ServerWebExchange exchange) {
        if (exchange.getResponse().getStatusCode() == null) {
            return UNKNOWN_STATUS;
        }

        return String.valueOf(exchange.getResponse().getStatusCode().value());
    }

    private String resolvePath(ServerWebExchange exchange) {
        if (httpMetricProperties.getPathMode() == HttpMetricProperties.PathMode.RAW) {
            String rawPath = exchange.getRequest().getPath().pathWithinApplication().value();
            return rawPath.isBlank() ? UNKNOWN_PATH : rawPath;
        }

        Object bestMatchingPattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (bestMatchingPattern != null) {
            return bestMatchingPattern.toString();
        }

        if (exchange.getResponse().getStatusCode() != null) {
            int status = exchange.getResponse().getStatusCode().value();

            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                return REDIRECTION_PATH;
            }

            if (status == 404) {
                return NOT_FOUND_PATH;
            }
        }

        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (path.isBlank() || "/".equals(path)) {
            return ROOT_PATH;
        }

        return UNKNOWN_PATH;
    }
}
