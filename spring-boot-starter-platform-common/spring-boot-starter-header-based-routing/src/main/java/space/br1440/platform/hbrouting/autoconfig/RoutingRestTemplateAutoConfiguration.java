package space.br1440.platform.hbrouting.autoconfig;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.hbrouting.http.RoutingRestTemplateInterceptor;

@AutoConfiguration(after = RoutingAutoConfiguration.class)
@ConditionalOnClass(RestTemplateCustomizer.class)
public class RoutingRestTemplateAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RoutingRestTemplateInterceptor routingRestTemplateInterceptor() {
        return new RoutingRestTemplateInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean(name = "routingRestTemplateCustomizer")
    public RestTemplateCustomizer routingRestTemplateCustomizer(
            RoutingRestTemplateInterceptor interceptor
    ) {
        return restTemplate -> restTemplate.getInterceptors().add(interceptor);
    }
}