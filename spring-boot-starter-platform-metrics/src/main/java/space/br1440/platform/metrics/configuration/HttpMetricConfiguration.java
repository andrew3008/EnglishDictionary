package space.br1440.platform.metrics.configuration;

import jakarta.servlet.Filter;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.configuration.properties.HttpMetricProperties;
import space.br1440.platform.metrics.http.HttpMetricsFilter;

@AutoConfiguration(after = {
        MetricsAutoConfiguration.class,
        CompositeMeterRegistryAutoConfiguration.class,
        CommonMetricConfiguration.class
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(Filter.class)
@ConditionalOnBean(PlatformMetrics.class)
public class HttpMetricConfiguration {

    @Bean
    @ConditionalOnMissingBean(HttpMetricsFilter.class)
    public HttpMetricsFilter httpMetricsFilter(PlatformMetrics platformMetrics,
                                               HttpMetricProperties httpMetricProperties) {
        return new HttpMetricsFilter(platformMetrics, httpMetricProperties);
    }
}
