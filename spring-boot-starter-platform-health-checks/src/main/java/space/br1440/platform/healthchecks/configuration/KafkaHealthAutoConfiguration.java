package space.br1440.platform.healthchecks.configuration;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import space.br1440.platform.healthchecks.kafka.*;

@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter({KafkaAutoConfiguration.class, MetricsAutoConfiguration.class})
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(KafkaHealthProperties.class)
public class KafkaHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaTemplate.class)
    public KafkaProducerHealthCheck kafkaProducerHealthCheck(KafkaTemplate<String, String> kafkaTemplate) {
        return new KafkaProducerHealthCheck(kafkaTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaConsumerHealthCheck kafkaConsumerHealthCheck(KafkaHealthProperties properties) {
        return new KafkaConsumerHealthCheck(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaTopicHealthCheck kafkaTopicHealthCheck(KafkaHealthProperties properties) {
        return new KafkaTopicHealthCheck(properties);
    }

    @Bean
    @ConditionalOnBean(AdminClient.class)
    @ConditionalOnProperty(prefix = "platform.health.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
    public KafkaHealthIndicator kafkaHealthIndicatorDelegate(AdminClient adminClient,
                                                             KafkaHealthProperties properties,
                                                             ObjectProvider<KafkaProducerHealthCheck> producerHealthCheckProvider,
                                                             KafkaConsumerHealthCheck consumerHealthCheck,
                                                             KafkaTopicHealthCheck topicHealthCheck) {
        return new KafkaHealthIndicator(adminClient, properties, producerHealthCheckProvider.getIfAvailable(), consumerHealthCheck, topicHealthCheck);
    }
}
