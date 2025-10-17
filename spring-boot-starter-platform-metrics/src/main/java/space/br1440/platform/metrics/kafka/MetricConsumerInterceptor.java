package space.br1440.platform.metrics.kafka;

import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.metrics.MetricBuilder;
import space.br1440.platform.metrics.SpringContext;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class MetricConsumerInterceptor<K, V> implements ConsumerInterceptor<K, V> {

    private String clientId = "unknown";
    private String groupId = "unknown";
    private static final String CLIENT_ID_KEY = "client.id";
    private static final String GROUP_ID_KEY = "group.id";

    private final ThreadLocal<Timer.Sample> sampleThreadLocal = new ThreadLocal<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricConsumerInterceptor.class);

    private MetricBuilder metricBuilder = SpringContext.getBean(MetricBuilder.class);


    @Override
    public ConsumerRecords<K, V> onConsume(ConsumerRecords<K, V> records) {
        if (records.isEmpty()) {
            return records;
        }
        try {
            //lag start
            sampleThreadLocal.set(Timer.start());
            Iterator<ConsumerRecord<K, V>> iteratorRecords = records.iterator();
            records.forEach(record -> {
                //count total
                metricBuilder.kafkaConsumerTotal(record.topic(), clientId, groupId).increment();

                //wait
                Timer timer = metricBuilder.kafkaConsumerWait(record.topic(), clientId, groupId);
                timer.record(System.currentTimeMillis() - record.timestamp(), TimeUnit.MILLISECONDS);
            });
        } catch (Exception e) {
            LOGGER.warn("Error while create metric.", e);
        }
        return records;
    }

    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
        try {
            //checkWait
            offsets.forEach((partition, offset) -> {
                var sampler = sampleThreadLocal.get();
                if (sampler != null) {
                    sampler.stop(
                            metricBuilder.kafkaConsumerLag(partition.topic(), clientId, groupId)
                    );
                } else {
                    LOGGER.debug("Sampler is null, can't write lag metric for topic-partition: {} and offset {}", partition, offset);
                }

            });
        } catch (Exception e) {
            LOGGER.info("Error on commit metric", e);
        } finally {
            sampleThreadLocal.remove();
        }
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
        try {
            clientId = Optional.ofNullable((String) configs.get(CLIENT_ID_KEY)).orElse("unknown");
            groupId = Optional.ofNullable((String) configs.get(GROUP_ID_KEY)).orElse("unknown");
            LOGGER.info("Found client id: {}", clientId);
        } catch (Exception e) {
            LOGGER.warn("Can't get kafka configuration", e);
        }
    }
}
