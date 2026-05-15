package space.br1440.platform.metrics.configuration;

import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.server.WebFilter;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.configuration.properties.HttpMetricProperties;
import space.br1440.platform.metrics.webflux.WebfluxMetricsFilter;

@AutoConfiguration(after = {
        MetricsAutoConfiguration.class,
        CompositeMeterRegistryAutoConfiguration.class,
        CommonMetricConfiguration.class
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(WebFilter.class)
@ConditionalOnBean(PlatformMetrics.class)
public class HttpWebfluxMetricConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WebfluxMetricsFilter httpWebfluxMetricsFilter(PlatformMetrics platformMetrics,
                                                         HttpMetricProperties httpMetricProperties) {
        return new WebfluxMetricsFilter(platformMetrics, httpMetricProperties);
    }
}
