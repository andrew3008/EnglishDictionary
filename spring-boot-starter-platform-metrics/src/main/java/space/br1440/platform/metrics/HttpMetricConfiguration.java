package space.br1440.platform.metrics;

import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import space.br1440.platform.metrics.config.HttpMetricProperties;
import space.br1440.platform.metrics.http.HttpMetricsFilter;

@Configuration
@ConditionalOnWebApplication
@ConditionalOnClass(Filter.class)
public class HttpMetricConfiguration {

    @Bean
    public OncePerRequestFilter createOncePerRequestFilter(MetricBuilder metricBuilder,
                                                           HttpMetricProperties httpMetricProperties) {
        return new HttpMetricsFilter(metricBuilder, httpMetricProperties);
    }
}