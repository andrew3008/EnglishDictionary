package space.br1440.platform.metrics.internal.factory;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.experimental.UtilityClass;
import space.br1440.platform.metrics.MetricLabelRulesApplier;
import space.br1440.platform.metrics.configuration.properties.GrpcMetricsProperties;
import space.br1440.platform.metrics.configuration.properties.HttpMetricProperties;
import space.br1440.platform.metrics.configuration.properties.KafkaMetricsProperties;

import java.time.Duration;
import java.util.List;

@UtilityClass
public final class MetricFactories {

    public static HttpMetricFactory http(MeterRegistry meterRegistry,
                                         MetricLabelRulesApplier metricLabelRulesApplier,
                                         HttpMetricProperties httpMetricProperties,
                                         List<Duration> slo) {
        return new HttpMetricFactory(meterRegistry, metricLabelRulesApplier, httpMetricProperties, slo);
    }

    public static GrpcMetricFactory grpc(MeterRegistry meterRegistry,
                                         MetricLabelRulesApplier metricLabelRulesApplier,
                                         GrpcMetricsProperties grpcMetricsProperties,
                                         List<Duration> slo) {
        return new GrpcMetricFactory(meterRegistry, metricLabelRulesApplier, grpcMetricsProperties, slo);
    }

    public static KafkaPlatformMetricFactory kafka(MeterRegistry meterRegistry,
                                                   MetricLabelRulesApplier metricLabelRulesApplier,
                                                   KafkaMetricsProperties kafkaMetricsProperties,
                                                   List<Duration> slo) {
        return new KafkaPlatformMetricFactory(meterRegistry, metricLabelRulesApplier, kafkaMetricsProperties, slo);
    }
}
