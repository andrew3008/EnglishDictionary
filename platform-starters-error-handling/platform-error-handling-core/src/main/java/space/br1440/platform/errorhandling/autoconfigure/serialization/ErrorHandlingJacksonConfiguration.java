package space.br1440.platform.errorhandling.autoconfigure.serialization;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "com.fasterxml.jackson.core.JsonProcessingException")
public class ErrorHandlingJacksonConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "jacksonExceptionStrategy")
    public TopExceptionHandlingStrategy jacksonExceptionStrategy(CauseChainResolver causeChainResolver) {
        return new space.br1440.platform.errorhandling.core.strategy.top.JacksonExceptionStrategy(causeChainResolver);
    }

    @Bean
    @ConditionalOnMissingBean(name = "jacksonRootCauseStrategy")
    public RootCauseExceptionHandlingStrategy jacksonRootCauseStrategy(StackHintExtractor stackHintExtractor) {
        return new space.br1440.platform.errorhandling.core.strategy.cause.serialization.JacksonRootCauseExceptionStrategy(stackHintExtractor);
    }
}