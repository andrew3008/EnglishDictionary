package space.br1440.platform.errorhandling.autoconfigure.network;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.network.DnsResolutionExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.network.HttpIntegrationExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.network.SslExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.network.TimeoutExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;

@Configuration(proxyBeanMethods = false)
public class ErrorHandlingNetworkConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "timeoutExceptionStrategy")
    public RootCauseExceptionHandlingStrategy timeoutExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new TimeoutExceptionStrategy(stackHintExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "dnsResolutionExceptionStrategy")
    public RootCauseExceptionHandlingStrategy dnsResolutionExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new DnsResolutionExceptionStrategy(stackHintExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "httpIntegrationExceptionStrategy")
    public RootCauseExceptionHandlingStrategy httpIntegrationExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new HttpIntegrationExceptionStrategy(stackHintExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "sslExceptionStrategy")
    public RootCauseExceptionHandlingStrategy sslExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new SslExceptionStrategy(stackHintExtractor);
    }
}
