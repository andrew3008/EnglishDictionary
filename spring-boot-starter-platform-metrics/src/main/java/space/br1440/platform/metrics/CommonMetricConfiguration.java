package space.br1440.platform.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.metrics.config.HttpMetricProperties;
import space.br1440.platform.metrics.config.MetricLabelRulesApplier;
import space.br1440.platform.metrics.config.MetricLabelRulesProperties;
import space.br1440.platform.metrics.endpoint.BuildInfoEndpoint;
import space.br1440.platform.metrics.endpoint.BuildInfoMetric;

@Configuration
@ComponentScan("space.br1440.platform.metrics")
@EnableConfigurationProperties({
        HttpMetricProperties.class,
        MetricLabelRulesProperties.class
})
public class CommonMetricConfiguration {

    @Bean
    public MetricLabelRulesApplier metricLabelRulesApplier(MetricLabelRulesProperties properties) {
        return new MetricLabelRulesApplier(properties);
    }

    @Bean
    public MetricBuilder metricBuilder(MeterRegistry meterRegistry,
                                       MetricLabelRulesApplier metricLabelRulesApplier) {
        return new MetricBuilder(meterRegistry, metricLabelRulesApplier);
    }

    @Bean
    public SpringContext springContext() {
        return new SpringContext();
    }


    @Bean
    public BuildInfoMetric buildInfoMetric(MeterRegistry meterRegistry) {
        return new BuildInfoMetric(meterRegistry);
    }

    @Bean
    public BuildInfoEndpoint buildInfoEndpoint(BuildInfoMetric buildInfoMetric) {
        return new BuildInfoEndpoint(buildInfoMetric);
    }
}