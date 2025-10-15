package space.br1440.platform.metrics.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.HashMap;
import java.util.Map;

public class KafkaConsumerFactoryPostProcessor implements BeanPostProcessor {

    private final static Logger LOGGER = LoggerFactory.getLogger(KafkaConsumerFactoryPostProcessor.class);
    public static final String METRIC_CONSUMER_INTERCEPTOR = "space.br1440.platform.metrics.kafka.MetricConsumerInterceptor";

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        // Для KafkaListenerContainerFactory
        if (bean instanceof ConcurrentKafkaListenerContainerFactory) {
            return wrapContainerFactory((ConcurrentKafkaListenerContainerFactory<?, ?>) bean);
        }

        // Для ConsumerFactory
        if (bean instanceof ConsumerFactory) {
            return wrapConsumerFactory((ConsumerFactory<?, ?>) bean);
        }

        return bean;
    }

    private ConcurrentKafkaListenerContainerFactory<?, ?> wrapContainerFactory(
            ConcurrentKafkaListenerContainerFactory<?, ?> factory) {

        ConsumerFactory<?, ?> consumerFactory = factory.getConsumerFactory();
        if (consumerFactory != null) {
            factory.setConsumerFactory(wrapConsumerFactory(consumerFactory));
        }
        return factory;
    }

    private ConsumerFactory wrapConsumerFactory(ConsumerFactory<?, ?> consumerFactory) {
        if (consumerFactory instanceof DefaultKafkaConsumerFactory) {
            DefaultKafkaConsumerFactory<?, ?> defaultFactory =
                    (DefaultKafkaConsumerFactory<?, ?>) consumerFactory;

            Map<String, Object> configs = new HashMap<>(defaultFactory.getConfigurationProperties());
            LOGGER.info("Found ConsumerFactory: {}", configs);

            // Добавляем интерцептор в конфигурацию
            addInterceptorConfig(configs);

            return new DefaultKafkaConsumerFactory<>(configs);
        }
        return consumerFactory;
    }

    private void addInterceptorConfig(Map<String, Object> configs) {
        var interceptorsConfig = (String) configs.get(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG);
        if (interceptorsConfig != null ) {
            if (!interceptorsConfig.contains(METRIC_CONSUMER_INTERCEPTOR)) {
                interceptorsConfig = interceptorsConfig + "," + METRIC_CONSUMER_INTERCEPTOR;
            }
        } else {
            interceptorsConfig = METRIC_CONSUMER_INTERCEPTOR;
        }
        configs.put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, interceptorsConfig);
    }
}
