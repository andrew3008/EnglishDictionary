package space.br1440.platform.metrics.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;

public class KafkaProducerFactoryPostProcessor implements BeanPostProcessor {

    private final static Logger LOGGER = LoggerFactory.getLogger(KafkaProducerFactoryPostProcessor.class);

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {

        if(bean instanceof ProducerFactory) {
            ProducerFactory producerFactory = (ProducerFactory) bean;
            LOGGER.info("Found ProducerFactory: {}", producerFactory.getConfigurationProperties());
            var mapProperties = new HashMap<>(producerFactory.getConfigurationProperties());
            var interceptorsConfig = mapProperties.get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG);
            interceptorsConfig = interceptorsConfig != null ?
                    interceptorsConfig + ", space.br1440.platform.metrics.kafka.MetricProducerInterceptor" :
                    "space.br1440.platform.metrics.kafka.MetricProducerInterceptor";
           mapProperties.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, interceptorsConfig);
            producerFactory.updateConfigs(mapProperties);
        }
        return bean;
    }
}
