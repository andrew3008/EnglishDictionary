package space.br1440.platform.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.util.CollectionUtils;
import space.br1440.platform.metrics.configuration.properties.GrpcMetricsProperties;
import space.br1440.platform.metrics.configuration.properties.HttpMetricProperties;
import space.br1440.platform.metrics.configuration.properties.KafkaMetricsProperties;
import space.br1440.platform.metrics.configuration.properties.PlatformMetricsSloProperties;
import space.br1440.platform.metrics.internal.factory.GrpcMetricFactory;
import space.br1440.platform.metrics.internal.factory.HttpMetricFactory;
import space.br1440.platform.metrics.internal.factory.KafkaPlatformMetricFactory;
import space.br1440.platform.metrics.internal.factory.MetricFactories;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class PlatformMetrics {

    private final HttpMetricFactory httpMetricFactory;
    private final GrpcMetricFactory grpcMetricFactory;
    private final KafkaPlatformMetricFactory kafkaPlatformMetricFactory;

    public PlatformMetrics(MeterRegistry meterRegistry,
                           MetricLabelRulesApplier metricLabelRulesApplier,
                           HttpMetricProperties httpMetricProperties,
                           GrpcMetricsProperties grpcMetricsProperties,
                           KafkaMetricsProperties kafkaMetricsProperties,
                           PlatformMetricsSloProperties sloProperties) {
        List<Duration> slo = CollectionUtils.isEmpty(sloProperties.getSlo())
                ? List.of()
                : List.copyOf(sloProperties.getSlo());

        this.httpMetricFactory = MetricFactories.http(meterRegistry, metricLabelRulesApplier, httpMetricProperties, slo);
        this.grpcMetricFactory = MetricFactories.grpc(meterRegistry, metricLabelRulesApplier, grpcMetricsProperties, slo);
        this.kafkaPlatformMetricFactory = MetricFactories.kafka(meterRegistry, metricLabelRulesApplier, kafkaMetricsProperties, slo);
    }

    public Timer grpcTimer(String methodName, String service, String code) {
        return grpcMetricFactory.grpcTimer(methodName, service, code);
    }

    public Counter grpcReceived(String methodName, String service) {
        return grpcMetricFactory.grpcReceived(methodName, service);
    }

    public Counter grpcSent(String methodName, String service, String code) {
        return grpcMetricFactory.grpcSent(methodName, service, code);
    }

    public Counter grpcStreamMessageSent(String methodName, String service) {
        return grpcMetricFactory.grpcStreamMessageSent(methodName, service);
    }

    public Counter grpcStreamMessageReceived(String methodName, String service) {
        return grpcMetricFactory.grpcStreamMessageReceived(methodName, service);
    }

    public Timer httpTimer(String method, String path, String status) {
        return httpMetricFactory.httpTimer(method, path, status);
    }

    public Counter httpResponseCounter(String method, String path, String status) {
        return httpMetricFactory.httpResponseCounter(method, path, status);
    }

    public Timer kafkaConsumerLag(String topic, String clientId, String consumerGroup) {
        return kafkaPlatformMetricFactory.kafkaConsumerLag(topic, clientId, consumerGroup);
    }

    public Timer kafkaConsumerWait(String topic, String clientId, String consumerGroup) {
        return kafkaPlatformMetricFactory.kafkaConsumerWait(topic, clientId, consumerGroup);
    }

    public Counter kafkaConsumerTotal(String topic, String clientId, String consumerGroup, String result) {
        return kafkaPlatformMetricFactory.kafkaConsumerTotal(topic, clientId, consumerGroup, result);
    }

    public void recordConsumerWaitMillis(String topic, String clientId, String consumerGroup, long waitMs) {
        if (waitMs < 0) {
            return;
        }

        kafkaConsumerWait(topic, clientId, consumerGroup)
                .record(waitMs, TimeUnit.MILLISECONDS);
    }

    public void recordConsumerLagMillis(String topic, String clientId, String consumerGroup, long recordTimestampMs) {
        long lagMs = System.currentTimeMillis() - recordTimestampMs;
        if (lagMs < 0) {
            lagMs = 0;
        }

        kafkaConsumerLag(topic, clientId, consumerGroup)
                .record(lagMs, TimeUnit.MILLISECONDS);
    }

    public Timer kafkaConsumerProcessing(String topic, String clientId, String consumerGroup,
                                         String outcome, String exceptionClass) {
        return kafkaPlatformMetricFactory.kafkaConsumerProcessing(topic, clientId, consumerGroup, outcome, exceptionClass);
    }

    public Timer kafkaBatchProcessing(String topic, String clientId, String consumerGroup,
                                      String outcome, String exceptionClass) {
        return kafkaPlatformMetricFactory.kafkaBatchProcessing(topic, clientId, consumerGroup, outcome, exceptionClass);
    }

    public DistributionSummary kafkaBatchMessagesTotal(String topic, String clientId, String consumerGroup) {
        return kafkaPlatformMetricFactory.kafkaBatchMessagesTotal(topic, clientId, consumerGroup);
    }

    public void recordProcessDuration(String topic, String clientId, String consumerGroup,
                                      String outcome, String exceptionClass, long durationNanos) {
        kafkaConsumerProcessing(topic, clientId, consumerGroup, outcome, exceptionClass)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordBatchProcessDuration(String topic, String clientId, String consumerGroup,
                                           String outcome, String exceptionClass, long durationNanos) {
        kafkaBatchProcessing(topic, clientId, consumerGroup, outcome, exceptionClass)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordBatchSize(String topic, String clientId, String consumerGroup, int size) {
        kafkaBatchMessagesTotal(topic, clientId, consumerGroup).record(size);
    }

    public Timer kafkaProducerLag(String topic, String clientId) {
        return kafkaPlatformMetricFactory.kafkaProducerLag(topic, clientId);
    }

    public Counter kafkaProducerTotal(String topic, String clientId, String result) {
        return kafkaPlatformMetricFactory.kafkaProducerTotal(topic, clientId, result);
    }
}
