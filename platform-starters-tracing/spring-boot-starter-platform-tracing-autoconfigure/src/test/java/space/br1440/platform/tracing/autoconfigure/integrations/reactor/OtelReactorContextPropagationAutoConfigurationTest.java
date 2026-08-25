package space.br1440.platform.tracing.autoconfigure.integrations.reactor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

class OtelReactorContextPropagationAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner reactiveRunner =
            new ReactiveWebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OtelReactorContextPropagationAutoConfiguration.class));

    private final ApplicationContextRunner disabledRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OtelReactorContextPropagationAutoConfiguration.class))
                    .withPropertyValues("platform.tracing.enabled=false");

    @Test
    void registersPropagationInReactiveApplication() {
        // given
        ReactiveWebApplicationContextRunner runner = reactiveRunner;

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OtelReactorContextPropagation.class);
        });
    }

    @Test
    void doesNotRegisterWhenTracingDisabled() {
        // given
        ApplicationContextRunner runner = disabledRunner;

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OtelReactorContextPropagation.class);
        });
    }
}
