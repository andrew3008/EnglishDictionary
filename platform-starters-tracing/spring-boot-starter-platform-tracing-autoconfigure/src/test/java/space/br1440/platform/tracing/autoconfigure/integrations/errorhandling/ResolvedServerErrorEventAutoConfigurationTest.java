package space.br1440.platform.tracing.autoconfigure.integrations.errorhandling;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import space.br1440.platform.errorhandling.mvc.observability.ExceptionObservabilityHook;
import space.br1440.platform.tracing.autoconfigure.exception.ExceptionRecordingAutoConfiguration;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;

class ResolvedServerErrorEventAutoConfigurationTest {

    private static final AutoConfigurations CONFIGURATIONS = AutoConfigurations.of(
            ExceptionRecordingAutoConfiguration.class,
            ResolvedServerErrorEventAutoConfiguration.class
    );

    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner()
            .withConfiguration(CONFIGURATIONS);

    @Test
    void processedAfterExceptionRecordingAutoConfiguration() {
        // given
        AutoConfiguration annotation = ResolvedServerErrorEventAutoConfiguration.class.getAnnotation(AutoConfiguration.class);

        // when
        // then
        assertThat(annotation.after()).containsExactly(ExceptionRecordingAutoConfiguration.class);
    }

    @Test
    void registersHookForServletApplicationWithRecorder() {
        // when
        servletRunner.run(context -> {
            // then
            assertThat(context).hasSingleBean(ExceptionRecorder.class);
            assertThat(context).hasSingleBean(ExceptionObservabilityHook.class);
            assertThat(context.getBean(ExceptionObservabilityHook.class)).isInstanceOf(ResolvedServerErrorEventHook.class);
        });
    }

    @Test
    void skipsHookWhenTracingDisabled() {
        // when
        servletRunner.withPropertyValues("platform.tracing.enabled=false").run(context -> {
            // then
            assertThat(context).doesNotHaveBean(ExceptionRecorder.class);
            assertThat(context).doesNotHaveBean(ExceptionObservabilityHook.class);
        });
    }

    @Test
    void skipsConfigurationWhenErrorHandlingMvcIsAbsent() {
        // when
        servletRunner.withClassLoader(new FilteredClassLoader(ExceptionObservabilityHook.class)).run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ExceptionRecorder.class);
            assertThat(context).doesNotHaveBean(ResolvedServerErrorEventAutoConfiguration.class);
        });
    }

    @Test
    void skipsConfigurationForNonServletApplications() {
        // given
        ApplicationContextRunner plainRunner = new ApplicationContextRunner().withConfiguration(CONFIGURATIONS);
        ReactiveWebApplicationContextRunner reactiveRunner = new ReactiveWebApplicationContextRunner()
                .withConfiguration(CONFIGURATIONS);

        // when
        // then
        plainRunner.run(context -> assertThat(context).doesNotHaveBean(ExceptionObservabilityHook.class));
        reactiveRunner.run(context -> assertThat(context).doesNotHaveBean(ExceptionObservabilityHook.class));
    }
}
