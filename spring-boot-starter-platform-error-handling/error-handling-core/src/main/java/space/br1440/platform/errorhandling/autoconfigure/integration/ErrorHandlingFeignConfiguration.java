package space.br1440.platform.errorhandling.autoconfigure.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "feign.FeignException")
public class ErrorHandlingFeignConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "feignExceptionStrategy")
    public TopExceptionHandlingStrategy feignExceptionStrategy(CauseChainResolver causeChainResolver) {
        return new space.br1440.platform.errorhandling.core.strategy.top.FeignExceptionStrategy(causeChainResolver);
    }

    @Bean
    @ConditionalOnMissingBean(name = "feignRootCauseExceptionStrategy")
    public RootCauseExceptionHandlingStrategy feignRootCauseExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new space.br1440.platform.errorhandling.core.strategy.cause.integration.FeignRootCauseExceptionStrategy(stackHintExtractor);
    }
}