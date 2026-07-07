package space.br1440.platform.tracing.autoconfigure.async;

import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты {@link TracingAsyncContextAutoConfiguration}: проверка opt-in активации, регистрации
 * BPP и {@link ContextSnapshotFactory}, fallback для неизвестного режима.
 */
class TracingAsyncContextAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingAsyncContextAutoConfiguration.class));

    @Test
    void по_умолчанию_бины_не_регистрируются() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ThreadPoolTaskExecutorContextPropagationBeanPostProcessor.class);
        });
    }

    @Test
    void при_enabled_true_регистрируется_bpp_и_contextSnapshotFactory() {
        runner.withPropertyValues("platform.tracing.context-propagation.async.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ThreadPoolTaskExecutorContextPropagationBeanPostProcessor.class);
                    assertThat(context).hasSingleBean(ContextSnapshotFactory.class);
                });
    }

    @Test
    void неизвестный_mode_не_блокирует_активацию_но_логирует_warning() {
        // Forward-compatibility: при появлении новых режимов в v1.1 текущие сборки
        // должны корректно fallback'нуться, а не падать с BeanCreationException.
        runner.withPropertyValues(
                        "platform.tracing.context-propagation.async.enabled=true",
                        "platform.tracing.context-propagation.async.mode=future-unknown-mode")
                .run(context -> {
                    assertThat(context).hasSingleBean(ThreadPoolTaskExecutorContextPropagationBeanPostProcessor.class);
                });
    }

    @Test
    void existing_ContextSnapshotFactory_не_перетирается() {
        ContextSnapshotFactory custom = ContextSnapshotFactory.builder().build();
        runner.withPropertyValues("platform.tracing.context-propagation.async.enabled=true")
                .withBean(ContextSnapshotFactory.class, () -> custom)
                .run(context -> {
                    assertThat(context.getBean(ContextSnapshotFactory.class)).isSameAs(custom);
                });
    }
}
