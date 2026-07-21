package space.br1440.platform.tracing.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.tracing.api.context.RequestTraceContextSnapshot;
import space.br1440.platform.tracing.autoconfigure.errorhandling.RequestTraceContextSnapshotSupplier;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты {@link RequestContextSupplierAutoConfiguration}.
 * <p>
 * Главная цель — закрепить инвариант: bean {@code platformRequestTraceContextSnapshotSupplier}
 * регистрируется <b>всегда</b> при наличии модуля на classpath, в т.ч. при выключенной трассировке
 * ({@code platform.tracing.enabled=false}). Без этого инварианта downstream-слой (error-handling)
 * не получит снимок контекста для маппинга в {@code RequestContext}.
 */
class RequestContextSupplierAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RequestContextSupplierAutoConfiguration.class));

    @Test
    void supplier_регистрируется_по_умолчанию() {
        runner.run(context -> {
            assertThat(context).hasBean("platformRequestTraceContextSnapshotSupplier");
            assertThat(context.getBean("platformRequestTraceContextSnapshotSupplier"))
                    .isInstanceOf(RequestTraceContextSnapshotSupplier.class);
        });
    }

    @Test
    void supplier_регистрируется_при_отключённой_трассировке() {
        // Главный регресс-тест: при platform.tracing.enabled=false bean всё равно есть.
        runner.withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasBean("platformRequestTraceContextSnapshotSupplier");

                    @SuppressWarnings("unchecked")
                    Supplier<RequestTraceContextSnapshot> supplier =
                            (Supplier<RequestTraceContextSnapshot>) context.getBean("platformRequestTraceContextSnapshotSupplier");

                    // Smoke-проверка: вызов get() не бросает исключений при отсутствии активного span'а.
                    RequestTraceContextSnapshot ctx = supplier.get();
                    assertThat(ctx).isNotNull();
                    assertThat(ctx.traceId()).isNull();
                    assertThat(ctx.spanId()).isNull();
                });
    }

    @Test
    void пользовательский_bean_замещает_дефолтный() {
        runner.withUserConfiguration(CustomSupplierConfig.class)
                .run(context -> {
                    assertThat(context).hasBean("platformRequestTraceContextSnapshotSupplier");
                    @SuppressWarnings("unchecked")
                    Supplier<RequestTraceContextSnapshot> supplier =
                            (Supplier<RequestTraceContextSnapshot>) context.getBean("platformRequestTraceContextSnapshotSupplier");
                    RequestTraceContextSnapshot ctx = supplier.get();
                    assertThat(ctx.correlationId()).isEqualTo("custom-correlation");
                });
    }

    /**
     * Кастомная конфигурация для проверки {@code @ConditionalOnMissingBean(name=...)}.
     */
    static class CustomSupplierConfig {

        @Bean(name = "platformRequestTraceContextSnapshotSupplier")
        public Supplier<RequestTraceContextSnapshot> platformRequestTraceContextSnapshotSupplier() {
            return () -> new RequestTraceContextSnapshot(null, "custom-correlation", null, null);
        }
    }
}
