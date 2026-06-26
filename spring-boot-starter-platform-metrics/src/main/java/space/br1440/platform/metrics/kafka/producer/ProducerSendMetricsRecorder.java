package space.br1440.platform.metrics.kafka.producer;

import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.PlatformMetrics;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
final class ProducerSendMetricsRecorder {

    private final AtomicBoolean recorded = new AtomicBoolean();
    private final PlatformMetrics platformMetrics;
    private final String topic;
    private final String clientId;
    private final long startNano;

    ProducerSendMetricsRecorder(PlatformMetrics platformMetrics, String topic, String clientId, long startNano) {
        this.platformMetrics = platformMetrics;
        this.topic = topic;
        this.clientId = clientId;
        this.startNano = startNano;
    }

    void success() {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        try {
            platformMetrics.kafkaProducerTotal(topic, clientId, MetricTags.RESULT_SUCCESS).increment();
            platformMetrics.kafkaProducerLag(topic, clientId)
                    .record(System.nanoTime() - startNano, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            log.debug("Failed to record platform producer success metrics, topic={}", topic, e);
        }
    }

    void failure() {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        try {
            platformMetrics.kafkaProducerTotal(topic, clientId, MetricTags.RESULT_FAILURE).increment();
        } catch (Exception e) {
            log.debug("Failed to record platform producer failure metrics, topic={}", topic, e);
        }
    }
}
