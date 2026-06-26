package space.br1440.platform.errorhandling.autoconfigure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.core.strategy.cause.messaging.RabbitMqExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

/**
 * Автоконфигурация {@link RabbitMqExceptionStrategy} — стратегии классификации
 * AMQP/RabbitMQ-исключений на двух уровнях:
 * <ul>
 *   <li>Spring AMQP типы ({@code AmqpException}, {@code AmqpAuthenticationException},
 *       {@code AmqpRejectAndDontRequeueException}, ...);</li>
 *   <li>протокольные {@code com.rabbitmq.client.ShutdownSignalException} с реальными
 *       AMQP reply-кодами (404 NOT_FOUND, 403 ACCESS_REFUSED, 320 CONNECTION_FORCED, ...).</li>
 * </ul>
 *
 * <p>Активируется при наличии {@code org.springframework.amqp.AmqpException} на classpath.
 * Если на classpath есть только нативный {@code com.rabbitmq.client.ShutdownSignalException}
 * без Spring AMQP — стратегия не регистрируется (нет смысла без обёрток Spring).
 *
 * @see RabbitMqExceptionStrategy
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.amqp.AmqpException")
public class ErrorHandlingRabbitMqConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "rabbitMqExceptionStrategy")
    public RootCauseExceptionHandlingStrategy rabbitMqExceptionStrategy(
            StackHintExtractor stackHintExtractor,
            ErrorHandlingProperties properties) {
        boolean includeBrokerCoordinates = properties.getMessaging().getRabbit().isIncludeBrokerCoordinates();
        return new RabbitMqExceptionStrategy(includeBrokerCoordinates, stackHintExtractor);
    }
}
