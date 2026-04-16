package space.br1440.platform.hbrouting.autoconfig;

import io.grpc.ClientInterceptor;
import io.grpc.ServerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.grpc.server.GlobalServerInterceptor;
import space.br1440.platform.hbrouting.config.RoutingProperties;
import space.br1440.platform.hbrouting.grpc.RoutingGrpcClientInterceptor;
import space.br1440.platform.hbrouting.grpc.RoutingGrpcServerInterceptor;
import space.br1440.platform.hbrouting.support.RoutingHeaderValidator;

@AutoConfiguration(after = RoutingAutoConfiguration.class)
@ConditionalOnClass({
        ClientInterceptor.class,
        ServerInterceptor.class,
        GlobalClientInterceptor.class,
        GlobalServerInterceptor.class
})
public class RoutingGrpcAutoConfiguration {

    @Bean
    @Order(100)
    @GlobalClientInterceptor
    @ConditionalOnMissingBean(name = "routingGrpcClientInterceptor")
    public ClientInterceptor routingGrpcClientInterceptor() {
        return new RoutingGrpcClientInterceptor();
    }

    @Bean
    @Order(100)
    @GlobalServerInterceptor
    @ConditionalOnMissingBean(name = "routingGrpcServerInterceptor")
    public ServerInterceptor routingGrpcServerInterceptor(
            RoutingProperties properties,
            RoutingHeaderValidator validator
    ) {
        return new RoutingGrpcServerInterceptor(properties, validator);
    }
}