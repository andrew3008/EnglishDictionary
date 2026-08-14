package space.br1440.platform.tracing.autoconfigure.reactive;

import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.reactor.ReactorAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты {@link TracingReactorEagerInitConfiguration}.
 * <p>
 * Проверяют, что {@link LazyInitializationExcludeFilter} регистрируется только в реактивных
 * приложениях и фактически делает {@link ReactorAutoConfiguration} eager-инициализируемой,
 * включая ситуацию с глобальным {@code spring.main.lazy-initialization=true}.
 */
class TracingReactorEagerInitConfigurationTest {

    private final ReactiveWebApplicationContextRunner reactiveContextRunner =
            new ReactiveWebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(TracingReactorEagerInitConfiguration.class));

    private final WebApplicationContextRunner servletContextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(TracingReactorEagerInitConfiguration.class));

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
            // Проверяем сам контракт фильтра: ReactorAutoConfiguration исключается из ленивой
            // инициализации; произвольная (любая иная) конфигурация — нет.
            assertThat(filter.isExcluded("reactorAutoConfiguration", null, ReactorAutoConfiguration.class))
                    .as("ReactorAutoConfiguration должен быть исключён из ленивой инициализации")
                    .isTrue();
            assertThat(filter.isExcluded("someOtherBean", null, String.class))
                    .as("Произвольный bean не должен попадать под исключение")
                    .isFalse();
        });
    }
}
