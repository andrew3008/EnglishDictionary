package space.br1440.platform.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

public class MetricBuilder {
    public static final String METHOD = "method";
    public static final String PATH = "path";
    public static final String STATUS = "status";
    public static final String TOPIC = "topic";
    public static final String CLIENT_ID = "client_id";
    public static final String CONSUMER_GROUP = "consumer_group";
    private final MeterRegistry meterRegistry;

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

    public static final String RPC_METHOD = "rpc_method";
    public static final String RPC_STATUS_CODE = "rpc_status_code";
    public static final String SERVICE = "service";

    public MetricBuilder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer grpcTimer(String methodName, String service, String code) {
        return Timer.builder(grpcTimerMetricName)
                .tags(RPC_METHOD, methodName, RPC_STATUS_CODE, code, SERVICE, service)
                .serviceLevelObjectives(Duration.ofSeconds(10))
                .sla(MetricUtils.createSla(sla))
                .register(meterRegistry);
    }

    public Counter grpcResieved(String methodName, String service) {
        return meterRegistry.counter(grpcReceivedCounterMetricName, Tags
                .of(RPC_METHOD, methodName)
                .and(SERVICE, service));
    }

    public Counter grpcSent(String methodName, String service, String code) {
        return meterRegistry.counter(grpcSentCounterMetrcName, Tags
                .of(RPC_METHOD, methodName)
                .and(RPC_STATUS_CODE, code)
                .and(SERVICE, service));
    }


    public Timer httpTimer(String method, String path, String status) {
        return Timer.builder(httpTimerMetricName)
                .tags(METHOD, method, PATH, path, STATUS, status)
                .serviceLevelObjectives(Duration.ofSeconds(10))
                .sla(MetricUtils.createSla(sla))
                .register(meterRegistry);
    }

    public Timer kafkaConsumerLag(String topic, String clientId, String consumerGroup) {
        return Timer.builder(kafkaConsumerLag)
                .serviceLevelObjectives(Duration.ofSeconds(10))
                .sla(MetricUtils.createSla(sla))
                .tags(TOPIC, topic, CLIENT_ID, clientId, CONSUMER_GROUP, consumerGroup)
                .register(meterRegistry);
    }

    public Timer kafkaConsumerWait(String topic, String clientId, String consumerGroup) {
        return Timer.builder(kafkaConsumerWait)
                .serviceLevelObjectives(Duration.ofSeconds(10))
                .sla(MetricUtils.createSla(sla))
                .tags(TOPIC, topic, CLIENT_ID, clientId, CONSUMER_GROUP, consumerGroup)
                .register(meterRegistry);
    }

    public Counter kafkaConsumerError(String topic, String clientId, String consumerGroup) {
        return meterRegistry.counter(kafkaConsumerError,
                TOPIC, topic, CLIENT_ID, clientId, CONSUMER_GROUP, consumerGroup);
    }

    public Counter kafkaConsumerTotal(String topic, String clientId, String consumerGroup) {
        return meterRegistry.counter(kafkaConsumerTotal,
                TOPIC, topic, CLIENT_ID, clientId, CONSUMER_GROUP, consumerGroup);
    }

    public Timer kafkaProducerLag(String topic, String clientId) {
        return Timer.builder(kafkaProducerLag)
                .serviceLevelObjectives(Duration.ofSeconds(10))
                .sla(MetricUtils.createSla(sla))
                .tags(TOPIC, topic, CLIENT_ID, clientId)
                .register(meterRegistry);
    }

    public Counter kafkaProducerError(String topic, String clientId) {
        return meterRegistry.counter(kafkaProducerError,
                TOPIC, topic, CLIENT_ID, clientId);
    }

    public Counter kafkaProducerTotal(String topic, String clientId) {
        return meterRegistry.counter(kafkaProducerTotal,
                TOPIC, topic, CLIENT_ID, clientId);
    }

    public Counter httpResponseCounter(String method, String path, String status) {
        return meterRegistry.counter(httpCounterMetricName,
                METHOD, method,
                PATH, path,
                STATUS, status);
    }


}
