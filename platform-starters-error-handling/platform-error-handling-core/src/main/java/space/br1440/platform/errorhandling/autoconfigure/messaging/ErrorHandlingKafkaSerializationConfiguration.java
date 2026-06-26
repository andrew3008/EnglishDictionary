package space.br1440.platform.errorhandling.autoconfigure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.messaging.KafkaSerializationExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.apache.kafka.common.errors.SerializationException")
public class ErrorHandlingKafkaSerializationConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "kafkaSerializationExceptionStrategy")
    public RootCauseExceptionHandlingStrategy kafkaSerializationExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new KafkaSerializationExceptionStrategy(stackHintExtractor);
    }
}