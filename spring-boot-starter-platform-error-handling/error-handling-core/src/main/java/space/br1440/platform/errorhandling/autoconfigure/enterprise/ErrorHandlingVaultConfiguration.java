package space.br1440.platform.errorhandling.autoconfigure.enterprise;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.enterprise.VaultExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.vault.VaultException")
public class ErrorHandlingVaultConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "vaultExceptionStrategy")
    public RootCauseExceptionHandlingStrategy vaultExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new VaultExceptionStrategy(stackHintExtractor);
    }
}