package space.br1440.platform.errorhandling.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricSnapshot;
import space.br1440.platform.errorhandling.metrics.MicrometerExceptionMetricRecorder;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExceptionMetricRecorder selection contract")
class ExceptionMetricRecorderContractTest {

    private final ReactiveWebApplicationContextRunner reactiveRunner =
            new ReactiveWebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            JacksonAutoConfiguration.class,
                            ErrorHandlingCoreAutoConfiguration.class,
                            ErrorHandlingWebFluxAutoConfiguration.class)
                    );

    private final ApplicationContextRunner plainRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ErrorHandlingCoreAutoConfiguration.class));

    @Test
    @DisplayName("При MeterRegistry в контексте регистрируется Micrometer-recorder, Noop отсутствует")
    void meterRegistryPresent_micrometerWins_noopAbsent() {
        reactiveRunner
                .withUserConfiguration(MeterRegistryConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ExceptionMetricRecorder.class);

                    ExceptionMetricRecorder recorder = ctx.getBean(ExceptionMetricRecorder.class);
                    assertThat(recorder)
                            .as("При MeterRegistry должен активироваться MicrometerExceptionMetricRecorder")
                            .isInstanceOf(MicrometerExceptionMetricRecorder.class);

                    assertThat(ctx.getBeansOfType(NoopExceptionMetricRecorder.class))
                            .as("NoopExceptionMetricRecorder не должен присутствовать при наличии MeterRegistry")
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("Без MeterRegistry в контексте fallback на Noop-recorder")
    void noMeterRegistry_fallbackToNoop() {
        reactiveRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ExceptionMetricRecorder.class);

            ExceptionMetricRecorder recorder = ctx.getBean(ExceptionMetricRecorder.class);
            assertThat(recorder)
                    .as("Без MeterRegistry должен быть NoopExceptionMetricRecorder fallback")
                    .isInstanceOf(NoopExceptionMetricRecorder.class);
        });
    }

    @Test
    @DisplayName("Пользовательский bean ExceptionMetricRecorder предотвращает обе авто-конфигурации")
    void userProvidedRecorder_preventsBothAutoConfigs() {
        reactiveRunner
                .withUserConfiguration(MeterRegistryConfig.class, CustomRecorderConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ExceptionMetricRecorder.class);

                    ExceptionMetricRecorder recorder = ctx.getBean(ExceptionMetricRecorder.class);
                    assertThat(recorder).isInstanceOf(CustomRecorder.class);
                    assertThat(ctx.getBeansOfType(MicrometerExceptionMetricRecorder.class)).isEmpty();
                    assertThat(ctx.getBeansOfType(NoopExceptionMetricRecorder.class)).isEmpty();
                });
    }

    @Test
    @DisplayName("В non-reactive контексте core-autoconfig тоже выбирает Micrometer при наличии MeterRegistry")
    void plainContext_withMeterRegistry_micrometerWins() {
        plainRunner
                .withUserConfiguration(MeterRegistryConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ExceptionMetricRecorder.class);
                    assertThat(ctx.getBean(ExceptionMetricRecorder.class))
                            .isInstanceOf(MicrometerExceptionMetricRecorder.class);
                    assertThat(ctx.getBeansOfType(NoopExceptionMetricRecorder.class)).isEmpty();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomRecorderConfig {

        @Bean
        ExceptionMetricRecorder customExceptionMetricRecorder() {
            return new CustomRecorder();
        }
    }

    static final class CustomRecorder implements ExceptionMetricRecorder {

        @Override
        public void recordHandled(@NonNull ExceptionMetricSnapshot snapshot) {
        }

        @Override
        public void recordUnhandled(@NonNull ExceptionMetricSnapshot snapshot) {
        }
    }
}
