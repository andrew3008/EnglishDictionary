package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.reactor.ReactorAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableReactiveWebApplicationContext;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

class TracingReactorEagerInitConfigurationTest {

    private final ReactiveWebApplicationContextRunner lazyInitContextRunner = new ReactiveWebApplicationContextRunner()
            .withInitializer(context -> context.addBeanFactoryPostProcessor(
                    new LazyInitializationBeanFactoryPostProcessor())
            )
            .withConfiguration(AutoConfigurations.of(ReactorAutoConfiguration.class));

    @Test
    void reactorAutoConfigurationIsCreatedAtStartupUnderLazyInitialization() {
        lazyInitContextRunner
                .withConfiguration(AutoConfigurations.of(TracingReactorEagerInitConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(isCreatedDuringRefresh(context, ReactorAutoConfiguration.class))
                            .as("ReactorAutoConfiguration must be created eagerly despite lazy initialization")
                            .isTrue();
                });
    }

    @Test
    void bootCreatesReactorAutoConfigurationDuringRefreshUnderLazyInitialization() {
        lazyInitContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(isCreatedDuringRefresh(context, ReactorAutoConfiguration.class)).isTrue();
        });
    }

    @SuppressWarnings("SameParameterValue")
    private static boolean isCreatedDuringRefresh(AssertableReactiveWebApplicationContext context, Class<?> type) {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        String[] beanNames = beanFactory.getBeanNamesForType(type, true, false);
        return (beanNames.length == 1) && beanFactory.containsSingleton(beanNames[0]);
    }
}
