package space.br1440.platform.metrics.configuration;

import io.grpc.ServerInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.autoconfigure.server.GrpcServerAutoConfiguration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.grpc.CustomServerInterceptor;

@AutoConfiguration(after = {
        MetricsAutoConfiguration.class,
        CompositeMeterRegistryAutoConfiguration.class,
        CommonMetricConfiguration.class,
        GrpcServerAutoConfiguration.class
})
@ConditionalOnClass({ServerInterceptor.class, GlobalServerInterceptor.class})
@ConditionalOnBean(PlatformMetrics.class)
public class GrpcMetricConfiguration {

    @Bean
    @GlobalServerInterceptor
    @ConditionalOnMissingBean(CustomServerInterceptor.class)
    public CustomServerInterceptor grpcMetricsServerInterceptor(
            PlatformMetrics platformMetrics, MeterRegistry meterRegistry) {
        return new CustomServerInterceptor(platformMetrics, meterRegistry);
    }
}
