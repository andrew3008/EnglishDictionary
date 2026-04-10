package space.br1440.platform.metrics.http;

import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import space.br1440.platform.metrics.MetricBuilder;
import space.br1440.platform.metrics.config.HttpMetricProperties;

import java.io.IOException;

@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpMetricsFilter extends OncePerRequestFilter {

    private static final String UNKNOWN_PATH = "UNKNOWN";
    private static final String NOT_FOUND_PATH = "NOT_FOUND";
    private static final String REDIRECTION_PATH = "REDIRECTION";
    private static final String ROOT_PATH = "root";
    private static final String UNKNOWN_METHOD = "UNKNOWN";
    private static final String UNKNOWN_STATUS = "UNKNOWN";

    private final MetricBuilder metricBuilder;
    private final HttpMetricProperties httpMetricProperties;

    public HttpMetricsFilter(MetricBuilder metricBuilder,
                             HttpMetricProperties httpMetricProperties) {
        this.metricBuilder = metricBuilder;
        this.httpMetricProperties = httpMetricProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Timer.Sample sample = Timer.start();
        try {
            filterChain.doFilter(request, response);
        } finally {
            recordMetrics(request, response, sample);
        }
    }

    private void recordMetrics(HttpServletRequest request,
                               HttpServletResponse response,
                               Timer.Sample sample) {
        String method = resolveMethod(request);
        String status = resolveStatus(response);
        String path = resolvePath(request, response);

        sample.stop(metricBuilder.httpTimer(method, path, status));
        metricBuilder.httpResponseCounter(method, path, status).increment();
    }

    private String resolveMethod(HttpServletRequest request) {
        String method = request.getMethod();
        return method != null ? method : UNKNOWN_METHOD;
    }

    private String resolveStatus(HttpServletResponse response) {
        int status = response.getStatus();
        return status > 0 ? String.valueOf(status) : UNKNOWN_STATUS;
    }

    private String resolvePath(HttpServletRequest request, HttpServletResponse response) {
        if (httpMetricProperties.getPathMode() == HttpMetricProperties.PathMode.RAW) {
            String rawPath = request.getRequestURI();
            return (rawPath == null || rawPath.isBlank()) ? UNKNOWN_PATH : rawPath;
        }

        int status = response.getStatus();

        if (status >= 300 && status < 400) {
            return REDIRECTION_PATH;
        }
        if (status == HttpServletResponse.SC_NOT_FOUND) {
            return NOT_FOUND_PATH;
        }

        Object bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (bestMatchingPattern != null) {
            return bestMatchingPattern.toString();
        }

        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank() || "/".equals(uri)) {
            return ROOT_PATH;
        }

        return UNKNOWN_PATH;
    }
}