package space.br1440.platform.metrics.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerPostProcessor;

@Slf4j
@RequiredArgsConstructor
public class MetricProducerPostProcessorRegistrar implements BeanPostProcessor {

    private final ObjectProvider<MetricProducerPostProcessor> postProcessorProvider;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof DefaultKafkaProducerFactory<?, ?> producerFactory)) {
            return bean;
        }

        boolean alreadyRegistered = producerFactory.getPostProcessors().stream()
                .anyMatch(MetricProducerPostProcessor.class::isInstance);

        if (alreadyRegistered) {
            log.debug("DefaultKafkaProducerFactory '{}': MetricProducerPostProcessor already registered, skipping", beanName);
            return bean;
        }

        MetricProducerPostProcessor postProcessor = postProcessorProvider.getIfAvailable();
        if (postProcessor != null) {
            addPostProcessor(producerFactory, postProcessor);
            log.debug("DefaultKafkaProducerFactory '{}': MetricProducerPostProcessor registered", beanName);
        }

        return bean;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> void addPostProcessor(DefaultKafkaProducerFactory<K, V> factory,
                                                MetricProducerPostProcessor postProcessor) {
        factory.addPostProcessor((ProducerPostProcessor<K, V>) postProcessor);
    }
}
