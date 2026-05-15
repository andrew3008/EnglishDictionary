package space.br1440.platform.metrics.configuration;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.listener.BatchInterceptor;
import org.springframework.kafka.listener.RecordInterceptor;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.configuration.condition.ConditionalOnKafkaBootstrapServers;
import space.br1440.platform.metrics.kafka.consumer.BatchProcessInterceptor;
import space.br1440.platform.metrics.kafka.KafkaFactoryListenerRegistrar;
import space.br1440.platform.metrics.kafka.KafkaStreamsListenerRegistrar;
import space.br1440.platform.metrics.kafka.producer.MetricProducerPostProcessorRegistrar;
import space.br1440.platform.metrics.kafka.consumer.ProcessRecordInterceptor;
import space.br1440.platform.metrics.kafka.producer.MetricProducerPostProcessor;

import java.util.Collections;

@AutoConfiguration(after = {
        MetricsAutoConfiguration.class,
        CompositeMeterRegistryAutoConfiguration.class
})
@ConditionalOnKafkaBootstrapServers
@ConditionalOnBean(MeterRegistry.class)
public class KafkaMetricsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MicrometerConsumerListener<Object, Object> micrometerConsumerListener(MeterRegistry meterRegistry) {
        return new MicrometerConsumerListener<>(meterRegistry, Collections.emptyList());
    }

    @Bean
    @ConditionalOnMissingBean
    public MicrometerProducerListener<Object, Object> micrometerProducerListener(MeterRegistry meterRegistry) {
        return new MicrometerProducerListener<>(meterRegistry, Collections.emptyList());
    }

    @Bean
    @ConditionalOnMissingBean(name = "kafkaFactoryListenerRegistrar")
    public static BeanPostProcessor kafkaFactoryListenerRegistrar(ObjectProvider<MicrometerConsumerListener<Object, Object>> consumerListener,
                                                                  ObjectProvider<MicrometerProducerListener<Object, Object>> producerListener) {
        return new KafkaFactoryListenerRegistrar(consumerListener, producerListener);
    }

    @Bean
    @ConditionalOnBean(PlatformMetrics.class)
    @ConditionalOnMissingBean
    public MetricProducerPostProcessor metricProducerPostProcessor(PlatformMetrics platformMetrics) {
        return new MetricProducerPostProcessor(platformMetrics);
    }

    @Bean
    @ConditionalOnMissingBean(name = "metricProducerPostProcessorRegistrar")
    public static BeanPostProcessor metricProducerPostProcessorRegistrar(ObjectProvider<MetricProducerPostProcessor> postProcessor) {
        return new MetricProducerPostProcessorRegistrar(postProcessor);
    }

    @Bean
    @ConditionalOnBean(PlatformMetrics.class)
    @ConditionalOnMissingBean
    public RecordInterceptor<Object, Object> processRecordInterceptor(PlatformMetrics platformMetrics) {
        return new ProcessRecordInterceptor(platformMetrics);
    }

    @Bean
    @ConditionalOnBean(PlatformMetrics.class)
    @ConditionalOnMissingBean
    public BatchInterceptor<Object, Object> batchProcessInterceptor(PlatformMetrics platformMetrics) {
        return new BatchProcessInterceptor(platformMetrics);
    }

    @Bean
    @ConditionalOnClass(name = "org.apache.kafka.streams.KafkaStreams")
    @ConditionalOnMissingBean(name = "kafkaStreamsListenerRegistrar")
    public static BeanPostProcessor kafkaStreamsListenerRegistrar(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new KafkaStreamsListenerRegistrar(meterRegistryProvider);
    }
}
