package space.br1440.platform.errorhandling.autoconfigure.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.core.strategy.cause.integration.WebSocketExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;

/**
 * Автоконфигурация стратегии классификации исключений transport-уровня WebSocket:
 * handshake / upgrade / codec / close ({@code org.springframework.web.socket.*},
 * {@code jakarta.websocket.*}, {@code javax.websocket.*}).
 *
 * <p>Конфигурация <b>не связана</b> со слоем {@code spring-messaging} (STOMP, Kafka, AMQP,
 * Integration): messaging-исключения поднимает по независимому условию {@code @ConditionalOnClass(Message.class)}.
 * Разделение намеренное — {@code spring-messaging} не требует {@code spring-websocket}, и наоборот.
 *
 * <p>Регистрируется только при наличии {@code spring-websocket} на classpath.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.web.socket.WebSocketException")
public class ErrorHandlingWebSocketConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "webSocketExceptionStrategy")
    public RootCauseExceptionHandlingStrategy webSocketExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new WebSocketExceptionStrategy(stackHintExtractor);
    }
}
