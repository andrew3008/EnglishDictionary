package space.br1440.platform.tracing.autoconfigure.integrations.aop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.support.FilteredClassLoader;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;

/**
 * Characterization: без AspectJ на classpath {@code TracingAopAutoConfiguration} не матчится.
 */
class TracingAopAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingAopAutoConfiguration.class))
            .withBean(TraceOperations.class, () -> NoopTraceOperations.INSTANCE);

    @Test
    void registersTracedAspectWhenAspectOnClasspath() {
        // when
        contextRunner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TracedAspect.class);
            assertThat(context).hasSingleBean(TracingAopAutoConfiguration.class);
        });
    }

    @Test
    void doesNotRegisterAspectWhenAspectMissingFromClasspath() {
        // given
        ApplicationContextRunner withoutAspect = contextRunner.withClassLoader(
                new FilteredClassLoader(
                        getClass().getClassLoader(),
                        Set.of("org.aspectj.lang.annotation")));

        // when
        withoutAspect.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TracedAspect.class);
            assertThat(context).doesNotHaveBean(TracingAopAutoConfiguration.class);
            assertThat(context.getClassLoader().getParent()).isNotNull();
            try {
                context.getClassLoader().loadClass(Aspect.class.getName());
                throw new AssertionError("Aspect should be filtered from classpath");
            } catch (ClassNotFoundException expected) {
                assertThat(expected.getMessage()).contains("Filtered");
            }
        });
    }
}
