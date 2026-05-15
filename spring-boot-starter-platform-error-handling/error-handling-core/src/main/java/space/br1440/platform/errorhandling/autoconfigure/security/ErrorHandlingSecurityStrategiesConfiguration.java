package space.br1440.platform.errorhandling.autoconfigure.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.strategy.cause.security.OAuth2AuthorizationExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.top.OAuth2ClientExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.top.SpringSecurityExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.security.core.AuthenticationException")
public class ErrorHandlingSecurityStrategiesConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "securityExceptionStrategy")
    public TopExceptionHandlingStrategy securityExceptionStrategy(CauseChainResolver causeChainResolver,
                                                                  ErrorHandlingProperties properties) {
        ErrorHandlingProperties.Security sec = properties.getSecurity();
        return new SpringSecurityExceptionStrategy(
                causeChainResolver,
                sec.getAuthFailureLogPolicy(),
                sec.getAccessDeniedLogPolicy());
    }

    @Bean
    @ConditionalOnMissingBean(name = "oauth2ClientExceptionStrategy")
    @ConditionalOnClass(name = "org.springframework.security.oauth2.core.OAuth2AuthenticationException")
    public TopExceptionHandlingStrategy oauth2ClientExceptionStrategy(CauseChainResolver causeChainResolver) {
        return new OAuth2ClientExceptionStrategy(causeChainResolver);
    }

    @Bean
    @ConditionalOnMissingBean(name = "oauth2AuthorizationExceptionStrategy")
    @ConditionalOnClass(name = "org.springframework.security.oauth2.core.OAuth2AuthorizationException")
    public RootCauseExceptionHandlingStrategy oauth2AuthorizationExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new OAuth2AuthorizationExceptionStrategy(stackHintExtractor);
    }
}
