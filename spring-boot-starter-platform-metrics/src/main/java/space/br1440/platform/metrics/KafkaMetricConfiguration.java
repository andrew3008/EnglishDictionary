package space.br1440.platform.metrics;


import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.RecordInterceptor;
import space.br1440.platform.metrics.kafka.KafkaConsumerFactoryPostProcessor;
import space.br1440.platform.metrics.kafka.KafkaProducerFactoryPostProcessor;
import space.br1440.platform.metrics.kafka.MetricErrorConsumerRecordInterceptor;

@Configuration
public class KafkaMetricConfiguration {


    @Bean
    @ConditionalOnClass(ProducerFactory.class)
    public BeanPostProcessor kafkaProducerFactoryPostProcessor() {
        return new KafkaProducerFactoryPostProcessor();
    }

    @Bean
    @ConditionalOnClass(ConsumerFactory.class)
    public BeanPostProcessor kafkaConsumerFactoryPostProcessor() {
        return new KafkaConsumerFactoryPostProcessor();
    }


    @Bean
    @ConditionalOnClass(RecordInterceptor.class)
    public RecordInterceptor metricRecordInterceptor(MetricBuilder metricBuilder) {
        return new MetricErrorConsumerRecordInterceptor(metricBuilder);
    }
}
