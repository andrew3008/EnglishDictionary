package space.br1440.platform.metrics.internal.factory;

import io.micrometer.core.instrument.*;
import space.br1440.platform.metrics.MetricLabelRulesApplier;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.configuration.properties.KafkaMetricsProperties;
import space.br1440.platform.metrics.internal.SloHelper;

import java.time.Duration;
import java.util.List;

public final class KafkaPlatformMetricFactory {

    private final MeterRegistry meterRegistry;
    private final MetricLabelRulesApplier metricLabelRulesApplier;
    private final KafkaMetricsProperties kafkaMetricsProperties;
    private final List<Duration> slo;

    KafkaPlatformMetricFactory(MeterRegistry meterRegistry,
                               MetricLabelRulesApplier metricLabelRulesApplier,
                               KafkaMetricsProperties kafkaMetricsProperties,
                               List<Duration> slo) {
        this.meterRegistry = meterRegistry;
        this.metricLabelRulesApplier = metricLabelRulesApplier;
        this.kafkaMetricsProperties = kafkaMetricsProperties;
        this.slo = slo;
    }

    public Timer kafkaConsumerLag(String topic, String clientId, String consumerGroup) {
        String name = kafkaMetricsProperties.getConsumerLagMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.TOPIC, topic,
                        MetricTags.CLIENT_ID, clientId,
                        MetricTags.CONSUMER_GROUP, consumerGroup)
        );

        Timer.Builder builder = Timer.builder(name).tags(tags);
        SloHelper.applyServiceLevelObjectives(builder, slo);
        return builder.register(meterRegistry);
    }

    public Timer kafkaConsumerWait(String topic, String clientId, String consumerGroup) {
        String name = kafkaMetricsProperties.getConsumerWaitMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.TOPIC, topic,
                        MetricTags.CLIENT_ID, clientId,
                        MetricTags.CONSUMER_GROUP, consumerGroup)
        );

        Timer.Builder builder = Timer.builder(name).tags(tags);
        SloHelper.applyServiceLevelObjectives(builder, slo);
        return builder.register(meterRegistry);
    }

    public Counter kafkaConsumerTotal(String topic, String clientId, String consumerGroup, String result) {
        String name = kafkaMetricsProperties.getConsumerTotalMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.TOPIC, topic,
                        MetricTags.CLIENT_ID, clientId,
                        MetricTags.CONSUMER_GROUP, consumerGroup,
                        MetricTags.RESULT, result)
        );

        return meterRegistry.counter(name, tags);
    }

    public Timer kafkaConsumerProcessing(String topic, String clientId, String consumerGroup,
                                         String outcome, String exceptionClass) {
        String resolvedExceptionClass = (exceptionClass != null) ? exceptionClass : MetricTags.EXCEPTION_NONE;
        String name = kafkaMetricsProperties.getConsumerProcessingMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.TOPIC, topic,
                        MetricTags.CLIENT_ID, clientId,
                        MetricTags.CONSUMER_GROUP, consumerGroup,
                        MetricTags.OUTCOME, outcome,
                        MetricTags.EXCEPTION_CLASS, resolvedExceptionClass)
        );

        return Timer.builder(name)
                .tags(tags)
                .register(meterRegistry);
    }

    public Timer kafkaBatchProcessing(String topic, String clientId, String consumerGroup,
                                      String outcome, String exceptionClass) {
        String resolvedExceptionClass = (exceptionClass != null) ? exceptionClass : MetricTags.EXCEPTION_NONE;
        String name = kafkaMetricsProperties.getBatchProcessingMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.TOPIC, topic,
                        MetricTags.CLIENT_ID, clientId,
                        MetricTags.CONSUMER_GROUP, consumerGroup,
                        MetricTags.OUTCOME, outcome,
                        MetricTags.EXCEPTION_CLASS, resolvedExceptionClass)
        );

        return Timer.builder(name)
                .tags(tags)
                .register(meterRegistry);
    }

    public DistributionSummary kafkaBatchMessagesTotal(String topic, String clientId, String consumerGroup) {
        String name = kafkaMetricsProperties.getBatchMessagesTotalMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.TOPIC, topic,
                        MetricTags.CLIENT_ID, clientId,
                        MetricTags.CONSUMER_GROUP, consumerGroup)
        );

        return DistributionSummary.builder(name)
                .baseUnit("records")
                .tags(tags)
                .register(meterRegistry);
    }

    public Timer kafkaProducerLag(String topic, String clientId) {
        String name = kafkaMetricsProperties.getProducerLagMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.TOPIC, topic,
                        MetricTags.CLIENT_ID, clientId)
        );

        Timer.Builder builder = Timer.builder(name).tags(tags);
        SloHelper.applyServiceLevelObjectives(builder, slo);
        return builder.register(meterRegistry);
    }

    public Counter kafkaProducerTotal(String topic, String clientId, String result) {
        String name = kafkaMetricsProperties.getProducerTotalMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.TOPIC, topic,
                        MetricTags.CLIENT_ID, clientId,
                        MetricTags.RESULT, result)
        );

        return meterRegistry.counter(name, tags);
    }
}
