package space.br1440.platform.hbrouting.autoconfig;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.hbrouting.config.RoutingProperties;
import space.br1440.platform.hbrouting.support.RoutingHeaderValidator;

@AutoConfiguration
@EnableConfigurationProperties(RoutingProperties.class)
@ConditionalOnProperty(prefix = "platform.hbrouting", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RoutingAutoConfiguration {

    @Bean
    public RoutingHeaderValidator routingHeaderValidator(RoutingProperties properties) {
        return new RoutingHeaderValidator(properties);
    }
}