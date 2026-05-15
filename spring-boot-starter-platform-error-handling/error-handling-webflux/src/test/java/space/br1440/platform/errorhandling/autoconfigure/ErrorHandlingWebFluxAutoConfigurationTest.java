package space.br1440.platform.errorhandling.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import space.br1440.platform.errorhandling.core.handler.ReactiveExceptionHandlerConfig;
import space.br1440.platform.errorhandling.core.handler.ReactiveExceptionHandlerDependencies;
import space.br1440.platform.errorhandling.core.handler.ReactiveGlobalExceptionHandler;
import space.br1440.platform.errorhandling.core.strategy.cause.WebFluxCauseExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.top.WebFluxExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.top.WebFluxFrameworkExceptionStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("ErrorHandlingWebFluxAutoConfiguration")
class ErrorHandlingWebFluxAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner contextRunner =
            new ReactiveWebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            JacksonAutoConfiguration.class,
                            ErrorHandlingCoreAutoConfiguration.class,
                            ErrorHandlingWebFluxAutoConfiguration.class));

    @Test
    @DisplayName("По умолчанию регистрирует все серверные бины")
    void defaultConfig_registersBeans() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ReactiveGlobalExceptionHandler.class);
            assertThat(ctx).hasSingleBean(ReactiveExceptionHandlerConfig.class);
            assertThat(ctx).hasSingleBean(ReactiveExceptionHandlerDependencies.class);
            assertThat(ctx).hasBean("errorHandlingJsonEncoder");
            assertThat(ctx.getBean("errorHandlingJsonEncoder")).isInstanceOf(Jackson2JsonEncoder.class);
            assertThat(ctx.getBean(ReactiveExceptionHandlerDependencies.class).jsonEncoder())
                    .as("dependencies wired with @Qualifier(\"errorHandlingJsonEncoder\") codec")
                    .isSameAs(ctx.getBean("errorHandlingJsonEncoder"));
            assertThat(ctx).hasSingleBean(WebFluxExceptionStrategy.class);
            assertThat(ctx).hasSingleBean(WebFluxFrameworkExceptionStrategy.class);
            assertThat(ctx).hasSingleBean(WebFluxCauseExceptionStrategy.class);
        });
    }

    @Test
    @DisplayName("enabled=false — бины не создаются")
    void disabledByProperty_noBeans() {
        contextRunner
                .withPropertyValues("platform.error-handling.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(ReactiveGlobalExceptionHandler.class);
                    assertThat(ctx).doesNotHaveBean(WebFluxExceptionStrategy.class);
                });
    }

    @Test
    @DisplayName("Пользовательский ReactiveGlobalExceptionHandler отключает авто-handler; Parameter Object-бины сохраняются")
    void customHandlerBean_preventsAutoHandler_parameterObjectBeansRemain() {
        contextRunner
                .withBean("reactiveGlobalExceptionHandler",
                        ReactiveGlobalExceptionHandler.class, () -> mock(ReactiveGlobalExceptionHandler.class))
                .run(ctx -> {
                    assertThat(ctx.getBeansOfType(ReactiveGlobalExceptionHandler.class)).hasSize(1);
                    assertThat(ctx).hasSingleBean(ReactiveExceptionHandlerConfig.class);
                    assertThat(ctx).hasSingleBean(ReactiveExceptionHandlerDependencies.class);
                });
    }

    @Test
    @DisplayName("Sprint 1, H0: ReactiveGlobalExceptionHandler имеет @Order(HIGHEST_PRECEDENCE) — контракт")
    void handler_hasHighestPrecedenceOrder() {
        Order orderAnnotation = AnnotationUtils.findAnnotation(
                ReactiveGlobalExceptionHandler.class, Order.class);

        assertThat(orderAnnotation)
                .as("ReactiveGlobalExceptionHandler должен быть аннотирован @Order")
                .isNotNull();
        assertThat(orderAnnotation.value())
                .as("ReactiveGlobalExceptionHandler.@Order должен быть Ordered.HIGHEST_PRECEDENCE")
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("В Servlet-контексте автоконфигурация не активируется (@ConditionalOnWebApplication REACTIVE)")
    void notActivatedInServletContext() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ErrorHandlingCoreAutoConfiguration.class,
                        ErrorHandlingWebFluxAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(ReactiveGlobalExceptionHandler.class);
                    assertThat(ctx).doesNotHaveBean(WebFluxExceptionStrategy.class);
                });
    }
}
