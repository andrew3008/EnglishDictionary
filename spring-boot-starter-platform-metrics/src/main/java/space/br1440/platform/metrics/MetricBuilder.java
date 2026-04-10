package space.br1440.platform.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import space.br1440.platform.metrics.config.MetricLabelRulesApplier;

import java.time.Duration;

public class MetricBuilder {
    public static final String METHOD = "method";
    public static final String PATH = "path";
    public static final String STATUS = "status";
    public static final String TOPIC = "topic";
    public static final String CLIENT_ID = "client_id";
    public static final String CONSUMER_GROUP = "consumer_group";

    public static final String RPC_METHOD = "rpc_method";
    public static final String RPC_STATUS_CODE = "rpc_status_code";
    public static final String SERVICE = "service";

    private final MeterRegistry meterRegistry;
    private final MetricLabelRulesApplier metricLabelRulesApplier;

    @Value("${platform-metric.http.timer-metric-name}")
    private String httpTimerMetricName;

    @Value("${platform-metric.http.counter-metric-name}")
    private String httpCounterMetricName;

    @Value("${platform-metric.kafka.consumer-lag-metric-name}")
    private String kafkaConsumerLag;

    @Value("${platform-metric.kafka.consumer-wait-metric-name}")
    private String kafkaConsumerWait;

    @Value("${platform-metric.kafka.consumer-error-metric-name}")
    private String kafkaConsumerError;

    @Value("${platform-metric.kafka.consumer-total-metric-name}")
    private String kafkaConsumerTotal;

    @Value("${platform-metric.kafka.producer-lag-metric-name}")
    private String kafkaProducerLag;

    @Value("${platform-metric.kafka.producer-error-metric-name}")
    private String kafkaProducerError;

    @Value("${platform-metric.kafka.producer-total-metric-name}")
    private String kafkaProducerTotal;

    @Value("${platform-metric.grpc.counter-sent-metric-name}")
    private String grpcSentCounterMetrcName;

    @Value("${platform-metric.grpc.counter-received-metric-name}")
    private String grpcReceivedCounterMetricName;

    @Value("${platform-metric.grpc.timer-metric-name}")
    private String grpcTimerMetricName;

    @Value("${platform-metric.sla}")
    private long[] sla;

    public MetricBuilder(MeterRegistry meterRegistry,
                         MetricLabelRulesApplier metricLabelRulesApplier) {
        this.meterRegistry = meterRegistry;
        this.metricLabelRulesApplier = metricLabelRulesApplier;
    }

    public Timer grpcTimer(String methodName, String service, String code) {
        Tags tags = apply(
                grpcTimerMetricName,
                Tags.of(RPC_METHOD, methodName, RPC_STATUS_CODE, code, SERVICE, service)
        );

        return Timer.builder(grpcTimerMetricName)
                .tags(tags)
                .serviceLevelObjectives(Duration.ofSeconds(10))
                .sla(MetricUtils.createSla(sla))
                .register(meterRegistry);
    }

    public Counter grpcResieved(String methodName, String service) {
        Tags tags = apply(
                grpcReceivedCounterMetricName,
                Tags.of(RPC_METHOD, methodName).and(SERVICE, service)
        );

        return meterRegistry.counter(grpcReceivedCounterMetricName, tags);
    }

    public Counter grpcSent(String methodName, String service, String code) {
        Tags tags = apply(
                grpcSentCounterMetrcName,
                Tags.of(RPC_METHOD, methodName)
                        .and(RPC_STATUS_CODE, code)
                        .and(SERVICE, service)
        );

        return meterRegistry.counter(grpcSentCounterMetrcName, tags);
    }

    public Timer httpTimer(String method, String path, String status) {
        Tags tags = apply(
                httpTimerMetricName,
                Tags.of(METHOD, method, PATH, path, STATUS, status)
        );

        return Timer.builder(httpTimerMetricName)
                .tags(tags)
                .serviceLevelObjectives(Duration.ofSeconds(10))
                .sla(MetricUtils.createSla(sla))
                .register(meterRegistry);
    }

    public Counter httpResponseCounter(String method, String path, String status) {
        Tags tags = apply(
                httpCounterMetricName,
                Tags.of(METHOD, method, PATH, path, STATUS, status)
        );

        return meterRegistry.counter(httpCounterMetricName, tags);
    }

    public Timer kafkaConsumerLag(String topic, String clientId, String consumerGroup) {
        Tags tags = apply(
                kafkaConsumerLag,
                Tags.of(TOPIC, topic, CLIENT_ID, clientId, CONSUMER_GROUP, consumerGroup)
        );

        return Timer.builder(kafkaConsumerLag)
                .serviceLevelObjectives(Duration.ofSeconds(10))
                .sla(MetricUtils.createSla(sla))
                .tags(tags)
                .register(meterRegistry);
    }

    public Timer kafkaConsumerWait(String topic, String clientId, String consumerGroup) {
        Tags tags = apply(
                kafkaConsumerWait,
                Tags.of(TOPIC, topic, CLIENT_ID, clientId, CONSUMER_GROUP, consumerGroup)
        );

        return Timer.builder(kafkaConsumerWait)
                .serviceLevelObjectives(Duration.ofSeconds(10))
                .sla(MetricUtils.createSla(sla))
                .tags(tags)
                .register(meterRegistry);
    }

    public Counter kafkaConsumerError(String topic, String clientId, String consumerGroup) {
        Tags tags = apply(
                kafkaConsumerError,
                Tags.of(TOPIC, topic, CLIENT_ID, clientId, CONSUMER_GROUP, consumerGroup)
        );

        return meterRegistry.counter(kafkaConsumerError, tags);
    }

    public Counter kafkaConsumerTotal(String topic, String clientId, String consumerGroup) {
        Tags tags = apply(
                kafkaConsumerTotal,
                Tags.of(TOPIC, topic, CLIENT_ID, clientId, CONSUMER_GROUP, consumerGroup)
        );

        return meterRegistry.counter(kafkaConsumerTotal, tags);
    }

    public Timer kafkaProducerLag(String topic, String clientId) {
        Tags tags = apply(
                kafkaProducerLag,
                Tags.of(TOPIC, topic, CLIENT_ID, clientId)
        );

        return Timer.builder(kafkaProducerLag)
                .serviceLevelObjectives(Duration.ofSeconds(10))
                .sla(MetricUtils.createSla(sla))
                .tags(tags)
                .register(meterRegistry);
    }

    public Counter kafkaProducerError(String topic, String clientId) {
        Tags tags = apply(
                kafkaProducerError,
                Tags.of(TOPIC, topic, CLIENT_ID, clientId)
        );

        return meterRegistry.counter(kafkaProducerError, tags);
    }

    public Counter kafkaProducerTotal(String topic, String clientId) {
        Tags tags = apply(
                kafkaProducerTotal,
                Tags.of(TOPIC, topic, CLIENT_ID, clientId)
        );

        return meterRegistry.counter(kafkaProducerTotal, tags);
    }

    private Tags apply(String metricName, Tags tags) {
        return metricLabelRulesApplier.apply(metricName, tags);
    }
}