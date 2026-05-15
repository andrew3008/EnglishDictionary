package space.br1440.platform.errorhandling.autoconfigure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.core.strategy.cause.messaging.KafkaBrokerExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

/**
 * Автоконфигурация {@link KafkaBrokerExceptionStrategy} — стратегии классификации
 * broker-уровневых Kafka-исключений (auth/authz/transactional/timeout/connection/generic).
 *
 * <p>Активируется при наличии {@code org.springframework.kafka.KafkaException} на classpath.
 * Регистрируется на одном уровне с {@link ErrorHandlingKafkaSerializationConfiguration}
 * (serialization-стратегия, order=430) и {@link ErrorHandlingKafkaTopConfiguration}
 * (top-level стратегия для прямых выбросов из контроллера).
 *
 * <h2>Соотношение order'ов</h2>
 * <ul>
 *   <li>{@code KAFKA_SERDE = 430} — serialization-ошибки (специфичный путь, идёт первым);</li>
 *   <li>{@code KAFKA_BROKER = 435} — broker connectivity/auth/transaction (этот класс);</li>
 *   <li>{@code RABBITMQ = 445} — Spring AMQP / native RabbitMQ;</li>
 *   <li>{@code SPRING_MESSAGING = 450} — generic fallback {@code spring-messaging}.</li>
 * </ul>
 *
 * @see KafkaBrokerExceptionStrategy
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.kafka.KafkaException")
public class ErrorHandlingKafkaBrokerConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "kafkaBrokerExceptionStrategy")
    public RootCauseExceptionHandlingStrategy kafkaBrokerExceptionStrategy(
            StackHintExtractor stackHintExtractor,
            ErrorHandlingProperties properties) {
        boolean includeBrokerCoordinates = properties.getMessaging().getKafka().isIncludeBrokerCoordinates();
        return new KafkaBrokerExceptionStrategy(includeBrokerCoordinates, stackHintExtractor);
    }
}
