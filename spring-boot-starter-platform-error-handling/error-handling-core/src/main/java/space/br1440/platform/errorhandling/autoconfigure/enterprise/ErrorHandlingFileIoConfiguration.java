package space.br1440.platform.errorhandling.autoconfigure.enterprise;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.enterprise.FileIoExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;

@Configuration(proxyBeanMethods = false)
public class ErrorHandlingFileIoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "fileIoExceptionStrategy")
    public RootCauseExceptionHandlingStrategy fileIoExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new FileIoExceptionStrategy(stackHintExtractor);
    }
}