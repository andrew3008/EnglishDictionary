package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.reactor.ReactorAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import reactor.core.publisher.Hooks;

class TracingReactorEagerInitConfigurationTest {

    private final ReactiveWebApplicationContextRunner reactiveContextRunner =
            new ReactiveWebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(TracingReactorEagerInitConfiguration.class));

    private final WebApplicationContextRunner servletContextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(TracingReactorEagerInitConfiguration.class));

    @AfterEach
    void tearDown() {
        Hooks.disableAutomaticContextPropagation();
    }

    @Test
    void filterIsRegisteredInReactiveApplication() {
        reactiveContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("platformTracingEagerReactorAutoConfigurationFilter");
            assertThat(context).hasSingleBean(LazyInitializationExcludeFilter.class);
        });
    }

    @Test
    void filterIsNotRegisteredInServletApplication() {
        servletContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("platformTracingEagerReactorAutoConfigurationFilter");
        });
    }

    @Test
    void filterExcludesReactorAutoConfigurationFromLazyInitialization() {
        reactiveContextRunner.run(context -> {
            LazyInitializationExcludeFilter filter =
                    context.getBean(LazyInitializationExcludeFilter.class);

            assertThat(filter.isExcluded("reactorAutoConfiguration", null, ReactorAutoConfiguration.class))
                    .as("ReactorAutoConfiguration must be excluded from lazy initialization")
                    .isTrue();
            assertThat(filter.isExcluded("someOtherBean", null, String.class))
                    .as("An unrelated bean must not be excluded")
                    .isFalse();
        });
    }

    @Test
    void defaultPropertyMakesBootReactorAutoConfigurationEnableTheHookWhenAppDoesNotSetIt() {
        Hooks.disableAutomaticContextPropagation();

        reactiveContextRunner
                .withConfiguration(AutoConfigurations.of(ReactorAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("spring.reactor.context-propagation"))
                            .isEqualTo("AUTO");
                    assertThat(Hooks.isAutomaticContextPropagationEnabled()).isTrue();
                });
    }

    @Test
    void explicitApplicationPropertyOverridesStarterDefault() {
        Hooks.enableAutomaticContextPropagation();

        reactiveContextRunner
                .withConfiguration(AutoConfigurations.of(ReactorAutoConfiguration.class))
                .withPropertyValues("spring.reactor.context-propagation=LIMITED")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("spring.reactor.context-propagation"))
                            .isEqualTo("LIMITED");
                });
    }
}
