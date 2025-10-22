package space.br1440.platform.metrics.http;

import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import space.br1440.platform.metrics.MetricBuilder;

import java.io.IOException;

@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpMetricsFilter extends OncePerRequestFilter {

    private final MetricBuilder metricBuilder;

    public HttpMetricsFilter(MetricBuilder metricBuilder) {
        this.metricBuilder = metricBuilder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var sampler = Timer.start();
        try {
            filterChain.doFilter(request, response);
        } finally {
            String path = request.getRequestURI();
            String method = request.getMethod();
            String status = String.valueOf(response.getStatus());

            // Метрика времени выполнения
            sampler.stop(metricBuilder.httpTimer(method, path, status));
            // Метрика количества запросов
            metricBuilder.httpResponseCounter(method, path, status).increment();
        }
    }
}