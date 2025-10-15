package space.br1440.platform.metrics;


import io.grpc.ServerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import space.br1440.platform.metrics.grpc.CustomServerInterceptor;

@Configuration
@ConditionalOnClass(ServerInterceptor.class)
public class GrpcMetricConfiguration {

    @Bean
    @GlobalServerInterceptor
    public ServerInterceptor createServerInterceptor(MetricBuilder metricBuilder) {
        return new CustomServerInterceptor(metricBuilder);
    }
}
