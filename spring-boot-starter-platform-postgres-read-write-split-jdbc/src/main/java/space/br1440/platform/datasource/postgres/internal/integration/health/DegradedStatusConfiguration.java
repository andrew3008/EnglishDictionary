package space.br1440.platform.datasource.postgres.internal.integration.health;

import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.StatusAggregator")
public class DegradedStatusConfiguration {

    private static final int ROUTING_DEGRADED_HTTP_CODE = 503;

    @Bean
    @ConditionalOnMissingBean(StatusAggregator.class)
    public StatusAggregator routingStatusAggregator() {
        return DegradedStatusSupport.newAggregator();
    }

    @Bean
    @ConditionalOnMissingBean(HttpCodeStatusMapper.class)
    public HttpCodeStatusMapper routingHttpCodeStatusMapper() {
        return DegradedStatusSupport.newHttpCodeStatusMapper(ROUTING_DEGRADED_HTTP_CODE);
    }
}
