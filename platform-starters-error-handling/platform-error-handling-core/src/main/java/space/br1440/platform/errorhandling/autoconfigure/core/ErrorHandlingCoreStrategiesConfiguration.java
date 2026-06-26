package space.br1440.platform.errorhandling.autoconfigure.core;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.core.BeanValidationExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.core.IllegalStateExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.core.ParseExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;

@Configuration(proxyBeanMethods = false)
public class ErrorHandlingCoreStrategiesConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "beanValidationExceptionStrategy")
    @ConditionalOnClass(name = "jakarta.validation.ConstraintViolationException")
    public RootCauseExceptionHandlingStrategy beanValidationExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new BeanValidationExceptionStrategy(stackHintExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "parseExceptionStrategy")
    public RootCauseExceptionHandlingStrategy parseExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new ParseExceptionStrategy(stackHintExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "illegalStateExceptionStrategy")
    public RootCauseExceptionHandlingStrategy illegalStateExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new IllegalStateExceptionStrategy(stackHintExtractor);
    }
}
