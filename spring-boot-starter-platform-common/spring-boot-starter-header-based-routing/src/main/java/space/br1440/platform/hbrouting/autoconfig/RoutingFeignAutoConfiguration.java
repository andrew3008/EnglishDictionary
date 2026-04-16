package space.br1440.platform.hbrouting.autoconfig;

import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.hbrouting.http.RoutingFeignInterceptor;

@AutoConfiguration(after = RoutingAutoConfiguration.class)
@ConditionalOnClass(RequestInterceptor.class)
public class RoutingFeignAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "routingFeignInterceptor")
    public RequestInterceptor routingFeignInterceptor() {
        return new RoutingFeignInterceptor();
    }
}