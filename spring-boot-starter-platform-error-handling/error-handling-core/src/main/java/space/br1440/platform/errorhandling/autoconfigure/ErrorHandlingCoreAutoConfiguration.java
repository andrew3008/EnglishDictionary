package space.br1440.platform.errorhandling.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import space.br1440.platform.errorhandling.autoconfigure.core.ErrorHandlingCoreConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.core.ErrorHandlingCoreStrategiesConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.core.ErrorHandlingTopStrategiesConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.enterprise.ErrorHandlingFileIoConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.enterprise.ErrorHandlingMinioConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.enterprise.ErrorHandlingVaultConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.integration.ErrorHandlingFeignConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.integration.ErrorHandlingWebSocketConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.messaging.ErrorHandlingKafkaBrokerConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.messaging.ErrorHandlingKafkaSerializationConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.messaging.ErrorHandlingKafkaTopConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.messaging.ErrorHandlingRabbitMqConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.messaging.ErrorHandlingSpringMessagingConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.network.ErrorHandlingNetworkConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.observation.ErrorHandlingMetricsConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.observation.ErrorHandlingNoopMetricsConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.security.ErrorHandlingSecurityStrategiesConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.serialization.ErrorHandlingJacksonConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.serialization.ErrorHandlingYamlConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.storage.ErrorHandlingRedisConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.storage.ErrorHandlingStorageCoreConfiguration;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

@AutoConfiguration
@EnableConfigurationProperties(ErrorHandlingProperties.class)
@ConditionalOnProperty(prefix = ErrorHandlingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@Import({
        ErrorHandlingCoreConfiguration.class,
        ErrorHandlingTopStrategiesConfiguration.class,
        ErrorHandlingCoreStrategiesConfiguration.class,

        // Security (strategies only, not Servlet infrastructure)
        ErrorHandlingSecurityStrategiesConfiguration.class,

        // Storage
        ErrorHandlingStorageCoreConfiguration.class,
        ErrorHandlingRedisConfiguration.class,

        // Messaging — три broker-уровневые root-cause стратегии (430/435/445/450)
        // плюс top-level Kafka-стратегия. Order'ы выстроены так:
        //   430 KAFKA_SERDE      → KafkaSerializationExceptionStrategy
        //   435 KAFKA_BROKER     → KafkaBrokerExceptionStrategy
        //   445 RABBITMQ         → RabbitMqExceptionStrategy
        //   450 SPRING_MESSAGING → SpringMessagingExceptionStrategy (generic fallback)
        ErrorHandlingKafkaSerializationConfiguration.class,
        ErrorHandlingKafkaBrokerConfiguration.class,
        ErrorHandlingKafkaTopConfiguration.class,
        ErrorHandlingRabbitMqConfiguration.class,
        ErrorHandlingSpringMessagingConfiguration.class,

        // Network
        ErrorHandlingNetworkConfiguration.class,

        // Integration
        ErrorHandlingFeignConfiguration.class,
        ErrorHandlingWebSocketConfiguration.class,

        // Serialization
        ErrorHandlingJacksonConfiguration.class,
        ErrorHandlingYamlConfiguration.class,

        // Enterprise
        ErrorHandlingVaultConfiguration.class,
        ErrorHandlingMinioConfiguration.class,
        ErrorHandlingFileIoConfiguration.class,

        // Observation
        ErrorHandlingMetricsConfiguration.class,
        ErrorHandlingNoopMetricsConfiguration.class,
})
public class ErrorHandlingCoreAutoConfiguration {
}
