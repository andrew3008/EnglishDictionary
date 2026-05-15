package space.br1440.platform.metrics.configuration;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.MetricLabelRulesApplier;
import space.br1440.platform.metrics.configuration.properties.HttpMetricProperties;
import space.br1440.platform.metrics.configuration.properties.MetricLabelRulesProperties;
import space.br1440.platform.metrics.configuration.properties.GrpcMetricsProperties;
import space.br1440.platform.metrics.configuration.properties.KafkaMetricsProperties;
import space.br1440.platform.metrics.configuration.properties.PlatformMetricsSloProperties;
import space.br1440.platform.metrics.buildinfo.BuildInfoEndpoint;
import space.br1440.platform.metrics.buildinfo.BuildInfoMetric;

@AutoConfiguration(after = {
        MetricsAutoConfiguration.class,
        CompositeMeterRegistryAutoConfiguration.class
})
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@EnableConfigurationProperties({
        HttpMetricProperties.class,
        MetricLabelRulesProperties.class,
        GrpcMetricsProperties.class,
        PlatformMetricsSloProperties.class,
        KafkaMetricsProperties.class
})
public class CommonMetricConfiguration {

    @Bean
    @ConditionalOnMissingBean(MetricLabelRulesApplier.class)
    public MetricLabelRulesApplier metricLabelRulesApplier(MetricLabelRulesProperties properties) {
        return new MetricLabelRulesApplier(properties);
    }

    @Bean
    @ConditionalOnMissingBean(PlatformMetrics.class)
    public PlatformMetrics PlatformMetrics(MeterRegistry meterRegistry,
                                       MetricLabelRulesApplier metricLabelRulesApplier,
                                       HttpMetricProperties httpMetricProperties,
                                       GrpcMetricsProperties grpcMetricsProperties,
                                       KafkaMetricsProperties kafkaMetricsProperties,
                                       PlatformMetricsSloProperties platformMetricsSloProperties) {
        return new PlatformMetrics(meterRegistry, metricLabelRulesApplier, httpMetricProperties,
                grpcMetricsProperties, kafkaMetricsProperties, platformMetricsSloProperties);
    }

    @Bean
    @ConditionalOnMissingBean(BuildInfoMetric.class)
    // Метаданные сборки — только если файл присутствует в runtime classpath (после processResources / JAR).
    @ConditionalOnResource(resources = "classpath:build-info.json")
    public BuildInfoMetric buildInfoMetric(MeterRegistry meterRegistry) {
        return new BuildInfoMetric(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(BuildInfoEndpoint.class)
    @ConditionalOnAvailableEndpoint
    // Тот же контракт, что и для метрики — без файла endpoint не регистрируется.
    @ConditionalOnResource(resources = "classpath:build-info.json")
    public BuildInfoEndpoint buildInfoEndpoint(BuildInfoMetric buildInfoMetric) {
        return new BuildInfoEndpoint(buildInfoMetric);
    }
}
