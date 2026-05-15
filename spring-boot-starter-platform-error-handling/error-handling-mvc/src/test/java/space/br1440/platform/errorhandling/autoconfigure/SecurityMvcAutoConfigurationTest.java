package space.br1440.platform.errorhandling.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import space.br1440.platform.errorhandling.core.security.ProblemSecurityBeanPostProcessor;
import space.br1440.platform.errorhandling.core.security.ProblemSupportConfigurer;
import space.br1440.platform.errorhandling.core.security.SecurityProblemSupport;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.testing.support.TestCorrelationIdResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("SecurityMvcAutoConfiguration")
class SecurityMvcAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityMvcAutoConfiguration.class))
            .withUserConfiguration(RequiredBeansConfiguration.class);

    @Nested
    @DisplayName("Регистрация бинов")
    class BeanRegistration {

        @Test
        @DisplayName("SecurityProblemSupport регистрируется при наличии Spring Security")
        void securityProblemSupport_registered() {
            // when / then
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(SecurityProblemSupport.class);
            });
        }

        @Test
        @DisplayName("ProblemSupportConfigurer регистрируется")
        void problemSupportConfigurer_registered() {
            // when / then
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(ProblemSupportConfigurer.class);
            });
        }

        @Test
        @DisplayName("ProblemSecurityBeanPostProcessor регистрируется по умолчанию")
        void beanPostProcessor_registeredByDefault() {
            // when / then
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(ProblemSecurityBeanPostProcessor.class);
            });
        }
    }

    @Nested
    @DisplayName("Условная активация")
    class ConditionalActivation {

        @Test
        @DisplayName("platform.error-handling.enabled=false -- бины не создаются")
        void disabledProperty_noBeansCreated() {
            // when / then
            contextRunner
                    .withPropertyValues("platform.error-handling.enabled=false")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(SecurityProblemSupport.class);
                        assertThat(context).doesNotHaveBean(ProblemSupportConfigurer.class);
                        assertThat(context).doesNotHaveBean(ProblemSecurityBeanPostProcessor.class);
                    });
        }

        @Test
        @DisplayName("security.auto-register-mode=NONE -- ProblemSecurityBeanPostProcessor не создаётся")
        void autoRegisterDisabled_noBeanPostProcessor() {
            // when / then
            contextRunner
                    .withPropertyValues("platform.error-handling.security.auto-register-mode=NONE")
                    .run(context -> {
                        assertThat(context).hasSingleBean(SecurityProblemSupport.class);
                        assertThat(context).hasSingleBean(ProblemSupportConfigurer.class);
                        assertThat(context).doesNotHaveBean(ProblemSecurityBeanPostProcessor.class);
                    });
        }

        @Test
        @DisplayName("security.auto-register-mode=ALL (default) -- ProblemSecurityBeanPostProcessor создаётся")
        void autoRegisterAllMode_beanPostProcessorCreated() {
            contextRunner
                    .withPropertyValues("platform.error-handling.security.auto-register-mode=ALL")
                    .run(context -> assertThat(context).hasSingleBean(ProblemSecurityBeanPostProcessor.class));
        }
    }

    @Nested
    @DisplayName("ConditionalOnMissingBean")
    class ConditionalOnMissingBeanTests {

        @Test
        @DisplayName("Пользовательский SecurityProblemSupport имеет приоритет")
        void customSupport_takesPrecedence() {
            // when / then
            contextRunner
                    .withUserConfiguration(CustomSecurityProblemSupportConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasSingleBean(SecurityProblemSupport.class);
                        assertThat(context.getBean(SecurityProblemSupport.class))
                                .isSameAs(context.getBean("customSecurityProblemSupport"));
                    });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RequiredBeansConfiguration {

        @Bean
        ErrorHandlingProperties errorHandlingProperties() {
            return new ErrorHandlingProperties();
        }

        @Bean("handlerExceptionResolver")
        HandlerExceptionResolver handlerExceptionResolver() {
            return mock(HandlerExceptionResolver.class);
        }

        @Bean
        space.br1440.platform.errorhandling.core.util.CorrelationIdResolver correlationIdResolver() {
            return TestCorrelationIdResolver.defaultInstance();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomSecurityProblemSupportConfiguration {

        @Bean("customSecurityProblemSupport")
        SecurityProblemSupport securityProblemSupport() {
            return new SecurityProblemSupport(mock(HandlerExceptionResolver.class));
        }
    }
}
