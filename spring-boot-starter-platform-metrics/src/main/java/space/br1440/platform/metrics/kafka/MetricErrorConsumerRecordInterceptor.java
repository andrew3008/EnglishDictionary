package space.br1440.platform.metrics.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.RecordInterceptor;
import space.br1440.platform.metrics.MetricBuilder;
import space.br1440.platform.metrics.MetricUtils;

public class MetricErrorConsumerRecordInterceptor implements RecordInterceptor<Object, Object> {

    private final MetricBuilder metricBuilder;

    private final static Logger LOGGER = LoggerFactory.getLogger(MetricErrorConsumerRecordInterceptor.class);


    public MetricErrorConsumerRecordInterceptor(MetricBuilder metricBuilder) {
        this.metricBuilder = metricBuilder;
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        return record;
    }

    @Override
    public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {

    }

    @Override
    public void failure(ConsumerRecord<Object, Object> record, Exception exception, Consumer<Object, Object> consumer) {
        try {
            if(exception != null) {
                String consumerGroup = consumer.groupMetadata().groupId();
                String clientId = MetricUtils.getClientIdForKafka(consumer.metrics());
                metricBuilder.kafkaConsumerError(record.topic(), clientId, consumerGroup).increment();
            }
        } catch (Exception e) {
            //nothing here
        }
    }

}