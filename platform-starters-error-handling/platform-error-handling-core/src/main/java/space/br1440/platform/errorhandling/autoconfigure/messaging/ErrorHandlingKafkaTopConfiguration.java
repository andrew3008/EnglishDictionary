package space.br1440.platform.errorhandling.autoconfigure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.strategy.top.KafkaExceptionStrategy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.kafka.KafkaException")
public class ErrorHandlingKafkaTopConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "kafkaExceptionStrategy")
    public TopExceptionHandlingStrategy kafkaExceptionStrategy(CauseChainResolver causeChainResolver,
                                                               ErrorHandlingProperties properties) {
        return new KafkaExceptionStrategy(causeChainResolver, properties.getMaxCauseDepth());
    }
}
