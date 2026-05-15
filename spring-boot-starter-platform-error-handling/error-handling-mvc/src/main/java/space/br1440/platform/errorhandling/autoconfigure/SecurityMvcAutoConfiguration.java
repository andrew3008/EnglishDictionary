package space.br1440.platform.errorhandling.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerExceptionResolver;
import space.br1440.platform.errorhandling.core.security.ProblemSecurityBeanPostProcessor;
import space.br1440.platform.errorhandling.core.security.ProblemSupportConfigurer;
import space.br1440.platform.errorhandling.core.security.SecurityProblemSupport;
import space.br1440.platform.errorhandling.core.util.CorrelationIdResolver;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

@AutoConfiguration(after = ErrorHandlingMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.security.web.AuthenticationEntryPoint")
@EnableConfigurationProperties(ErrorHandlingProperties.class)
@ConditionalOnProperty(prefix = ErrorHandlingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class SecurityMvcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SecurityProblemSupport securityProblemSupport(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
                                                         CorrelationIdResolver correlationIdResolver) {
        return new SecurityProblemSupport(resolver, correlationIdResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemSupportConfigurer problemSupportConfigurer(SecurityProblemSupport problemSupport) {
        return new ProblemSupportConfigurer(problemSupport);
    }

    /**
     * Условный bean: создаётся только в режиме {@code ALL}.
     * В режиме {@code NONE} пользователь подключает {@link ProblemSupportConfigurer} вручную
     * либо вообще не использует платформенный мост.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = ErrorHandlingProperties.PREFIX, name = "security.auto-register-mode", havingValue = "ALL", matchIfMissing = true)
    public static ProblemSecurityBeanPostProcessor problemSecurityBeanPostProcessor(ObjectProvider<SecurityProblemSupport> securityProblemSupportProvider) {
        return new ProblemSecurityBeanPostProcessor(securityProblemSupportProvider);
    }
}
