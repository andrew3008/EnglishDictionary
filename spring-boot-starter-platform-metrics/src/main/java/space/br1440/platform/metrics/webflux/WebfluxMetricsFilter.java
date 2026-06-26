package space.br1440.platform.metrics.webflux;

import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.RouterFunctions;
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
                .doFinally(signalType -> {
                    if (signalType == SignalType.CANCEL || signalType == SignalType.ON_ERROR) {
                        boolean isError = (signalType == SignalType.ON_ERROR);
                        recordMetrics(exchange, sample, recorded, isError);
                    }
                })
                .then(Mono.defer(() -> {
                    recordMetrics(exchange, sample, recorded, false);
                    return Mono.empty();
                }));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private void recordMetrics(ServerWebExchange exchange, Timer.Sample sample,
                               AtomicBoolean recorded, boolean isError) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }

        String method = resolveMethod(exchange);
        String status = resolveStatus(exchange, isError);
        String path = resolvePath(exchange);
        sample.stop(platformMetrics.httpTimer(method, path, status));
        platformMetrics.httpResponseCounter(method, path, status).increment();
    }

    private String resolveMethod(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod().name();
    }

    private String resolveStatus(ServerWebExchange exchange, boolean isError) {
        HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
        if (statusCode != null) {
            return String.valueOf(statusCode.value());
        }

        if (isError) {
            return UNKNOWN_STATUS;
        }

        return String.valueOf(HttpStatus.OK.value());
    }

    private String resolvePath(ServerWebExchange exchange) {
        if (httpMetricProperties.getPathMode() == HttpMetricProperties.PathMode.RAW) {
            String rawPath = exchange.getRequest().getPath().pathWithinApplication().value();
            return rawPath.isBlank() ? UNKNOWN_PATH : rawPath;
        }

        Object bestMatchingPattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (bestMatchingPattern != null) {
            String pattern = bestMatchingPattern.toString();
            if (exchange.getResponse().getStatusCode() != null) {
                int status = exchange.getResponse().getStatusCode().value();
                if (isRedirectStatus(status)) {
                    return REDIRECTION_PATH;
                }
            }

            if (isNotFound(exchange) && ("/**".equals(pattern) || pattern.endsWith("/**"))) {
                return NOT_FOUND_PATH;
            }

            if ("/".equals(pattern)) {
                return ROOT_PATH;
            }

            return pattern;
        }

        Object routerFunctionPattern = exchange.getAttribute(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE);
        if (routerFunctionPattern != null) {
            String pattern = routerFunctionPattern.toString();
            if (exchange.getResponse().getStatusCode() != null) {
                int status = exchange.getResponse().getStatusCode().value();
                if (isRedirectStatus(status)) {
                    return REDIRECTION_PATH;
                }
            }

            if (isNotFound(exchange) && ("/**".equals(pattern) || pattern.endsWith("/**"))) {
                return NOT_FOUND_PATH;
            }

            if ("/".equals(pattern)) {
                return ROOT_PATH;
            }

            return pattern;
        }

        if (exchange.getResponse().getStatusCode() != null) {
            int status = exchange.getResponse().getStatusCode().value();

            if (isRedirectStatus(status)) {
                return REDIRECTION_PATH;
            }

            if (status == HttpStatus.NOT_FOUND.value()) {
                return NOT_FOUND_PATH;
            }
        }

        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (path.isBlank() || "/".equals(path)) {
            return ROOT_PATH;
        }

        return UNKNOWN_PATH;
    }

    private boolean isNotFound(ServerWebExchange exchange) {
        HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
        return (statusCode != null) && (statusCode.value() == HttpStatus.NOT_FOUND.value());
    }

    private static boolean isRedirectStatus(int status) {
        return status == HttpStatus.MOVED_PERMANENTLY.value()
                || status == HttpStatus.FOUND.value()
                || status == HttpStatus.SEE_OTHER.value()
                || status == HttpStatus.TEMPORARY_REDIRECT.value()
                || status == HttpStatus.PERMANENT_REDIRECT.value();
    }
}
