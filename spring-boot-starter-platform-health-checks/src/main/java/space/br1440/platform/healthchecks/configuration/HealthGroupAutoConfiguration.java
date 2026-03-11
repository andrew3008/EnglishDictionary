package space.br1440.platform.healthchecks.configuration;

import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.SimpleHttpCodeStatusMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.healthchecks.common.HealthStatuses;
import space.br1440.platform.healthchecks.processor.ProbeHealthEndpointGroupsPostProcessor;

import java.util.Map;

@AutoConfiguration
@AutoConfigureAfter({KafkaHealthAutoConfiguration.class, PostgresHealthAutoConfiguration.class})
public class HealthGroupAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(HttpCodeStatusMapper.class)
    public HttpCodeStatusMapper httpCodeStatusMapper() {
        return new SimpleHttpCodeStatusMapper(Map.of(
                HealthStatuses.UP.getCode(),             200,
                HealthStatuses.DEGRADED.getCode(),       200,
                HealthStatuses.UNKNOWN.getCode(),        200,
                HealthStatuses.OUT_OF_SERVICE.getCode(), 503,
                HealthStatuses.DOWN.getCode(),           503
        ));
    }

    @Bean
    public ProbeHealthEndpointGroupsPostProcessor severityHealthEndpointGroupsPostProcessor(ApplicationContext context) {
        return new ProbeHealthEndpointGroupsPostProcessor(context);
    }
}
