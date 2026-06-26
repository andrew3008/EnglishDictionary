package space.br1440.platform.errorhandling.autoconfigure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.core.strategy.cause.messaging.SpringMessagingExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

/**
 * Автоконфигурация стратегии классификации исключений слоя
 * {@code org.springframework.messaging.*} независимо от транспортного адаптера.
 *
 * <h2>Перенос из {@code integration} package в {@code messaging}</h2>
 * <p>Стратегия исторически жила в пакете
 * {@code space.br1440.platform.errorhandling.core.strategy.cause.integration} с порядком 440;
 * после рефакторинга всех messaging-стратегий все три broker-уровневые стратегии
 * (Kafka serde / Kafka broker / RabbitMQ / Spring Messaging) собраны в одной
 * иерархии {@code messaging} с порядками 430/435/445/450 — это устраняет
 * undiscoverability ("где RabbitMQ?") и приводит к единообразию.
 *
 * <h2>Активация</h2>
 * <p>Активируется наличием класса {@code org.springframework.messaging.Message} на classpath.
 * Покрывает все стеки, построенные на {@code spring-messaging}:
 * <ul>
 *   <li>STOMP over WebSocket ({@code simp.*});</li>
 *   <li>Spring Kafka ({@code @KafkaListener});</li>
 *   <li>Spring AMQP / RabbitMQ ({@code MessagingMessageListenerAdapter});</li>
 *   <li>Spring Integration ({@code MessageChannel});</li>
 *   <li>Spring Cloud Stream (любой binder).</li>
 * </ul>
 *
 * <p>{@code spring-messaging} транзитивно подтягивается Spring Web/WebFlux/WebSocket,
 * поэтому стратегия с большой вероятностью будет активна в любом Spring Boot приложении —
 * это намеренное design-решение: generic fallback после специализированных
 * Kafka/RabbitMQ стратегий.
 *
 * @see SpringMessagingExceptionStrategy
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.messaging.Message")
public class ErrorHandlingSpringMessagingConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "springMessagingExceptionStrategy")
    public RootCauseExceptionHandlingStrategy springMessagingExceptionStrategy(
            StackHintExtractor stackHintExtractor,
            ErrorHandlingProperties properties) {
        boolean includeBrokerCoordinates =
                properties.getMessaging().getSpringMessaging().isIncludeBrokerCoordinates();
        return new SpringMessagingExceptionStrategy(includeBrokerCoordinates, stackHintExtractor);
    }
}
