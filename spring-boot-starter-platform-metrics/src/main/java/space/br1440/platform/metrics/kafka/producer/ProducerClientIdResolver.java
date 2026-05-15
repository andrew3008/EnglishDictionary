package space.br1440.platform.metrics.kafka.producer;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.springframework.util.StringUtils;

import java.util.Map;

@Slf4j
final class ProducerClientIdResolver {

    private static final String UNKNOWN = "unknown";
    private static final String CLIENT_ID_TAG = "client-id";

    private final Producer<?, ?> producer;
    private volatile String cachedClientId;

    ProducerClientIdResolver(Producer<?, ?> producer) {
        this.producer = producer;
    }

    String resolve() {
        String id = cachedClientId;
        if (id == null) {
            id = resolveFromMetrics();
            cachedClientId = id;
        }

        return id;
    }

    private String resolveFromMetrics() {
        try {
            for (Map.Entry<MetricName, ? extends Metric> entry : producer.metrics().entrySet()) {
                String tagValue = entry.getKey().tags().get(CLIENT_ID_TAG);
                if (StringUtils.hasText(tagValue)) {
                    return tagValue;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to retrieve client.id from producer metrics", e);
        }

        return UNKNOWN;
    }
}
