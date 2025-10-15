package space.br1440.platform.metrics;


import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.metrics.endpoint.BuildInfoEndpoint;

@Configuration
@ComponentScan("space.br1440.platform.metrics")
public class CommonMetricConfiguration {

    @Bean
    public MetricBuilder metricBuilder(MeterRegistry meterRegistry) {
        return new MetricBuilder(meterRegistry);
    }

    @Bean
    public SpringContext springContext() {
        return new SpringContext();
    }

    @Bean
    public BuildInfoEndpoint buildInfoEndpoint() {
        return new BuildInfoEndpoint();
    }
}
