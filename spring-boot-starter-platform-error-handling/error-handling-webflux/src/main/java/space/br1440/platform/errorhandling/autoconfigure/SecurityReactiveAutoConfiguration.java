package space.br1440.platform.errorhandling.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.server.SecurityWebFilterChain;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.core.handler.ReactiveGlobalExceptionHandler;
import space.br1440.platform.errorhandling.core.security.ReactiveSecurityProblemSupport;
import space.br1440.platform.errorhandling.core.security.ReactiveSecurityProblemSupportVerifier;

@AutoConfiguration(after = ErrorHandlingWebFluxAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(name = "org.springframework.security.web.server.ServerAuthenticationEntryPoint")
@EnableConfigurationProperties(ErrorHandlingProperties.class)
@ConditionalOnProperty(prefix = ErrorHandlingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class SecurityReactiveAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ReactiveSecurityProblemSupport reactiveSecurityProblemSupport(ReactiveGlobalExceptionHandler handler) {
        return new ReactiveSecurityProblemSupport(handler);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactiveSecurityProblemSupportVerifier reactiveSecurityProblemSupportVerifier(ObjectProvider<ReactiveSecurityProblemSupport> problemSupportProvider,
                                                                                         ObjectProvider<SecurityWebFilterChain> securityChainsProvider) {
        return new ReactiveSecurityProblemSupportVerifier(problemSupportProvider, securityChainsProvider);
    }
}
