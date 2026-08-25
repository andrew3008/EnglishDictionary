package space.br1440.platform.errorhandling.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.advice.GlobalExceptionHandler;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.strategy.top.SpringMvcExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;
import space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.testing.support.TestUnknownExceptionHandler;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorHandlingMvcAutoConfiguration")
class ErrorHandlingMvcAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ErrorHandlingMvcAutoConfiguration.class))
            .withUserConfiguration(RequiredBeansConfiguration.class);

    @Nested
    @DisplayName("Регистрация бинов")
    class BeanRegistration {

        @Test
        @DisplayName("GlobalExceptionHandler регистрируется при Servlet web application")
        void globalExceptionHandler_registered() {
            // when / then
            contextRunner.run(context -> assertThat(context).hasSingleBean(GlobalExceptionHandler.class));
        }

        @Test
        @DisplayName("SpringMvcExceptionStrategy регистрируется по умолчанию")
        void springMvcExceptionStrategy_registeredByDefault() {
            // when / then
            contextRunner.run(context -> assertThat(context).hasSingleBean(SpringMvcExceptionStrategy.class));
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
                    .run(context -> assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class));
        }

        @Test
        @DisplayName("Бины создаются по умолчанию без явного enabled=true")
        void defaultEnabled_beansCreated() {
            // when / then
            contextRunner.run(context -> assertThat(context).hasSingleBean(GlobalExceptionHandler.class));
        }
    }

    @Nested
    @DisplayName("Phase 3.5: AutoConfigureBefore контракт с ErrorMvcAutoConfiguration")
    class AutoConfigureOrder {

        @Test
        @DisplayName("Наш autoconfig объявляет AutoConfigureBefore(ErrorMvcAutoConfiguration)")
        void declaresAutoConfigureBeforeErrorMvc() {
            // given
            org.springframework.boot.autoconfigure.AutoConfigureBefore annotation =
                    ErrorHandlingMvcAutoConfiguration.class
                            .getAnnotation(org.springframework.boot.autoconfigure.AutoConfigureBefore.class);

            assertThat(annotation).as("@AutoConfigureBefore must be present").isNotNull();
            assertThat(annotation.value())
                    .contains(org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration.class);
        }

        @Test
        @DisplayName("@RestControllerAdvice ловит исключения раньше /error forward (BasicErrorController)")
        void restControllerAdvice_catchesBeforeErrorForward() {
            // when / then
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                assertThat(GlobalExceptionHandler.class.isAnnotationPresent(
                        org.springframework.web.bind.annotation.RestControllerAdvice.class)).isTrue();
            });
        }
    }

    @Nested
    @DisplayName("ConditionalOnMissingBean")
    class ConditionalOnMissingBeanTests {

        @Test
        @DisplayName("Пользовательский GlobalExceptionHandler имеет приоритет")
        void customHandler_takesPrecedence() {
            // when / then
            contextRunner
                    .withUserConfiguration(CustomHandlerConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                        assertThat(context.getBean(GlobalExceptionHandler.class))
                                .isSameAs(context.getBean("customGlobalExceptionHandler"));
                    });
        }

        @Test
        @DisplayName("Пользовательский SpringMvcExceptionStrategy вытесняет дефолтный (по типу)")
        void customStrategy_takesPrecedence() {
            // when / then
            contextRunner
                    .withUserConfiguration(CustomStrategyConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasSingleBean(SpringMvcExceptionStrategy.class);
                        assertThat(context.getBean(SpringMvcExceptionStrategy.class).supports(new RuntimeException()))
                                .isFalse();
                    });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RequiredBeansConfiguration {

        @Bean
        ErrorHandlingProperties errorHandlingProperties() {
            return new ErrorHandlingProperties();
        }

        @Bean("orderedTopExceptionHandlingStrategies")
        List<TopExceptionHandlingStrategy> strategies(CauseChainResolver causeChainResolver) {
            return List.of(new SpringMvcExceptionStrategy(causeChainResolver));
        }

        @Bean
        CauseChainResolver causeChainResolver() {
            return new CauseChainResolver(Collections.emptyList());
        }

        @Bean
        UnknownExceptionHandler unknownExceptionHandler() {
            return new TestUnknownExceptionHandler();
        }

        @Bean
        ExceptionMetricRecorder exceptionMetricRecorder() {
            return new NoopExceptionMetricRecorder();
        }

        @Bean
        ErrorEntryToDtoMapper errorEntryToDtoMapper() {
            return new ErrorEntryToDtoMapper();
        }

        @Bean("orderedErrorEntryEnrichers")
        List<ErrorEntryEnricher> entryEnrichers() {
            return Collections.emptyList();
        }

        @Bean
        StackHintExtractor stackHintExtractor() {
            return new StackHintExtractor(false, List.of());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomHandlerConfiguration {

        @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
        @Bean("customGlobalExceptionHandler")
        GlobalExceptionHandler globalExceptionHandler(ErrorHandlingProperties properties,
                                                      UnknownExceptionHandler unknownHandler,
                                                      ExceptionMetricRecorder metricsRecorder,
                                                      ErrorEntryToDtoMapper mapper,
                                                      StackHintExtractor extractor) {
            return new GlobalExceptionHandler(
                    properties,
                    Collections.emptyList(),
                    unknownHandler,
                    metricsRecorder,
                    new space.br1440.platform.errorhandling.mvc.internal.ProblemResponseFactory(mapper, "custom-app", properties),
                    new space.br1440.platform.errorhandling.mvc.internal.ExceptionLogger(properties),
                    space.br1440.platform.errorhandling.testing.support.TestCorrelationIdResolver.defaultInstance(),
                    Collections.emptyList(),
                    extractor,
                    Collections.emptyList(),
                    null
            );
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomStrategyConfiguration {

        @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
        @Bean
        SpringMvcExceptionStrategy customSpringMvcExceptionStrategy(CauseChainResolver causeChainResolver) {
            return new SpringMvcExceptionStrategy(causeChainResolver) {
                @Override
                public boolean supports(@NonNull Exception e) {
                    return false;
                }
            };
        }
    }
}
