package space.br1440.platform.metrics.kafka;

import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.metrics.MetricBuilder;
import space.br1440.platform.metrics.SpringContext;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class MetricProducerInterceptor implements ProducerInterceptor {

    private String clientId = "unknown";
    private static final String CLIENT_ID_KEY = "client.id";

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricProducerInterceptor.class);

    private MetricBuilder metricBuilder = SpringContext.getBean(MetricBuilder.class);

    @Override
    public ProducerRecord onSend(ProducerRecord record) {
        try {
            metricBuilder.kafkaProducerTotal(record.topic(), clientId).increment();

        } catch (Exception e) {
            LOGGER.warn("Can't create metric because", e);
        }

        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        try {
            if (exception != null) {
                try {
                    metricBuilder.kafkaProducerError(metadata.topic(), clientId).increment();
                } catch (Exception e) {
                    LOGGER.warn("Can't create metric because", e);
                }
            } else {
                Timer t = metricBuilder.kafkaProducerLag(metadata.topic(), clientId);
                t.record(System.currentTimeMillis() - metadata.timestamp(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            LOGGER.warn("Can't create metric because", e);
        }

    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
        try {
            clientId = Optional.ofNullable((String) configs.get(CLIENT_ID_KEY)).orElse("unknown");
            LOGGER.info("Found client id: {}", clientId);
        } catch (Exception e) {
            LOGGER.warn("Can't get kafka configuration", e);
        }
    }
}
