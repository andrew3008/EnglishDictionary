package space.br1440.platform.metrics.adr.support;

import io.micrometer.core.instrument.MeterRegistry;
import space.br1440.platform.metrics.MetricLabelRulesApplier;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.configuration.properties.GrpcMetricsProperties;
import space.br1440.platform.metrics.configuration.properties.HttpMetricProperties;
import space.br1440.platform.metrics.configuration.properties.KafkaMetricsProperties;
import space.br1440.platform.metrics.configuration.properties.MetricLabelRulesProperties;
import space.br1440.platform.metrics.configuration.properties.PlatformMetricsSloProperties;

/**
 * Фабрика {@link PlatformMetrics} для интеграционных тестов ADR.
 *
 * <p>Создаёт экземпляр с именами метрик, совпадающими с {@code default.properties},
 * без SLO-бакетов и без правил фильтрации тегов — чистая конфигурация
 * для верификации контракта ADR.</p>
 */
public final class PlatformMetricsFactory {

    private PlatformMetricsFactory() {
    }

    /** Создаёт {@link PlatformMetrics} с дефолтными именами метрик из {@code default.properties}. */
    public static PlatformMetrics create(MeterRegistry registry) {
        MetricLabelRulesApplier rulesApplier = new MetricLabelRulesApplier(new MetricLabelRulesProperties());

        HttpMetricProperties httpProps = new HttpMetricProperties()
                .setTimerMetricName("platform.http.server.requests")
                .setCounterMetricName("platform.http.requests");

        GrpcMetricsProperties grpcProps = new GrpcMetricsProperties()
                .setTimerMetricName("platform.grpc.server")
                .setCounterSentMetricName("platform.grpc.server.sent")
                .setCounterReceivedMetricName("platform.grpc.server.received")
                .setStreamMessagesSentMetricName("platform.grpc.server.stream.messages.sent")
                .setStreamMessagesReceivedMetricName("platform.grpc.server.stream.messages.received");

        KafkaMetricsProperties kafkaProps = new KafkaMetricsProperties()
                .setConsumerLagMetricName("platform_consumer_lag_seconds")
                .setConsumerWaitMetricName("platform_consumer_wait_seconds")
                .setConsumerTotalMetricName("platform_consumer_messages_total")
                .setConsumerProcessingMetricName("platform_consumer_processing_seconds")
                .setBatchProcessingMetricName("platform_consumer_batch_processing_seconds")
                .setBatchMessagesTotalMetricName("platform_consumer_batch_messages_total")
                .setProducerTotalMetricName("platform_producer_messages_total")
                .setProducerLagMetricName("platform_producer_lag_seconds");

        PlatformMetricsSloProperties sloProps = new PlatformMetricsSloProperties();

        return new PlatformMetrics(registry, rulesApplier, httpProps, grpcProps, kafkaProps, sloProps);
    }
}
