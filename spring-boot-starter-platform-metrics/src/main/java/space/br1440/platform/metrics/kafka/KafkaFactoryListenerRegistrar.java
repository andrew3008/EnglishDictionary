package space.br1440.platform.metrics.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.core.*;

@Slf4j
@RequiredArgsConstructor
public class KafkaFactoryListenerRegistrar implements BeanPostProcessor {

    private final ObjectProvider<MicrometerConsumerListener<Object, Object>> consumerListenerProvider;
    private final ObjectProvider<MicrometerProducerListener<Object, Object>> producerListenerProvider;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DefaultKafkaConsumerFactory<?, ?> consumerFactory) {
            boolean alreadyRegistered = consumerFactory.getListeners().stream()
                    .anyMatch(MicrometerConsumerListener.class::isInstance);

            if (alreadyRegistered) {
                log.debug("DefaultKafkaConsumerFactory '{}': MicrometerConsumerListener already registered, skipping", beanName);
            } else {
                MicrometerConsumerListener<?, ?> listener = consumerListenerProvider.getIfAvailable();
                if (listener != null) {
                    addConsumerListener(consumerFactory, listener);
                    log.debug("DefaultKafkaConsumerFactory '{}': MicrometerConsumerListener registered", beanName);
                }
            }
        }

        if (bean instanceof DefaultKafkaProducerFactory<?, ?> producerFactory) {
            boolean alreadyRegistered = producerFactory.getListeners().stream()
                    .anyMatch(MicrometerProducerListener.class::isInstance);

            if (alreadyRegistered) {
                log.debug("DefaultKafkaProducerFactory '{}': MicrometerProducerListener already registered, skipping", beanName);
            } else {
                MicrometerProducerListener<?, ?> listener = producerListenerProvider.getIfAvailable();
                if (listener != null) {
                    addProducerListener(producerFactory, listener);
                    log.debug("DefaultKafkaProducerFactory '{}': MicrometerProducerListener registered", beanName);
                }
            }
        }

        return bean;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> void addConsumerListener(DefaultKafkaConsumerFactory<K, V> factory,
                                                   MicrometerConsumerListener<?, ?> listener) {
        factory.addListener((ConsumerFactory.Listener<K, V>) listener);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> void addProducerListener(DefaultKafkaProducerFactory<K, V> factory,
                                                   MicrometerProducerListener<?, ?> listener) {
        factory.addListener((ProducerFactory.Listener<K, V>) listener);
    }
}
