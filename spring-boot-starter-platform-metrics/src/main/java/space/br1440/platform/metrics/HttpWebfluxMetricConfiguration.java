package space.br1440.platform.metrics;


import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import space.br1440.platform.metrics.webflux.http.WebfluxMetricsFilter;

@Configuration
@ConditionalOnWebApplication
@ConditionalOnClass({WebFilter.class, Mono.class})
public class HttpWebfluxMetricConfiguration {



    @Bean
    public WebfluxMetricsFilter httpWebfluxMetricsFilter(MetricBuilder metricBuilder) {
        return new WebfluxMetricsFilter(metricBuilder);
    }


}
