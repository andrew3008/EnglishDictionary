package space.br1440.platform.hbrouting.autoconfig;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;
import space.br1440.platform.hbrouting.http.RoutingWebClientFilter;

@AutoConfiguration(after = RoutingAutoConfiguration.class)
@ConditionalOnClass({WebClient.class, WebClientCustomizer.class})
public class RoutingWebClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RoutingWebClientFilter routingWebClientFilter() {
        return new RoutingWebClientFilter();
    }

    @Bean
    @ConditionalOnMissingBean(name = "routingWebClientCustomizer")
    public WebClientCustomizer routingWebClientCustomizer(RoutingWebClientFilter filter) {
        return builder -> builder.filter(filter);
    }
}