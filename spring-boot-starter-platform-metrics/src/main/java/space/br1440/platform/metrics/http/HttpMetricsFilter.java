package space.br1440.platform.metrics.http;

import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.configuration.properties.HttpMetricProperties;

import java.io.IOException;

@Slf4j
public class HttpMetricsFilter extends OncePerRequestFilter implements Ordered {

    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    private static final String UNKNOWN_PATH = "UNKNOWN";
    private static final String NOT_FOUND_PATH = "NOT_FOUND";
    private static final String REDIRECTION_PATH = "REDIRECTION";
    private static final String ROOT_PATH = "ROOT";

    private static final int HTTP_STATUS_PERMANENT_REDIRECT = 308;

    private final PlatformMetrics platformMetrics;
    private final HttpMetricProperties httpMetricProperties;

    public HttpMetricsFilter(PlatformMetrics platformMetrics,
                             HttpMetricProperties httpMetricProperties) {
        this.platformMetrics = platformMetrics;
        this.httpMetricProperties = httpMetricProperties;

        if (httpMetricProperties.getPathMode() == HttpMetricProperties.PathMode.RAW) {
            log.warn("""
                HttpMetricsFilter: PathMode.RAW включён. \
                Возможна cardinality explosion в метриках. \
                Рекомендуется PathMode.TEMPLATE (по умолчанию).\
            """);
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
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

        sample.stop(platformMetrics.httpTimer(method, path, status));
        platformMetrics.httpResponseCounter(method, path, status).increment();
    }

    private String resolveMethod(HttpServletRequest request) {
        return request.getMethod();
    }

    private String resolveStatus(HttpServletResponse response) {
        return String.valueOf(response.getStatus());
    }

    private String resolvePath(HttpServletRequest request, HttpServletResponse response) {
        if (httpMetricProperties.getPathMode() == HttpMetricProperties.PathMode.RAW) {
            String servletPath = request.getServletPath();
            if (StringUtils.hasText(servletPath)) {
                return servletPath;
            }

            String pathInfo = request.getPathInfo();
            if (StringUtils.hasText(pathInfo)) {
                return pathInfo;
            }

            String requestUri = request.getRequestURI();
            if (!StringUtils.hasText(requestUri)) {
                return UNKNOWN_PATH;
            }

            String contextPath = request.getContextPath();
            if (StringUtils.hasText(contextPath)) {
                requestUri = requestUri.substring(contextPath.length());
            }

            return StringUtils.hasText(requestUri) ? requestUri : UNKNOWN_PATH;
        }

        int status = response.getStatus();
        if (isRedirectStatus(status)) {
            return REDIRECTION_PATH;
        }

        Object bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (bestMatchingPattern != null) {
            String pattern = bestMatchingPattern.toString();
            if (status == HttpServletResponse.SC_NOT_FOUND
                    && ("/**".equals(pattern) || pattern.endsWith("/**"))) {
                return NOT_FOUND_PATH;
            }
            if ("/".equals(pattern)) {
                return ROOT_PATH;
            }
            return pattern;
        }

        if (status == HttpServletResponse.SC_NOT_FOUND) {
            return NOT_FOUND_PATH;
        }

        String servletPath = request.getServletPath();
        if (!StringUtils.hasText(servletPath) || "/".equals(servletPath)) {
            return ROOT_PATH;
        }

        return UNKNOWN_PATH;
    }

    private static boolean isRedirectStatus(int status) {
        return status == HttpServletResponse.SC_MOVED_PERMANENTLY
                || status == HttpServletResponse.SC_MOVED_TEMPORARILY
                || status == HttpServletResponse.SC_SEE_OTHER
                || status == HttpServletResponse.SC_TEMPORARY_REDIRECT
                || status == HTTP_STATUS_PERMANENT_REDIRECT;
    }
}
