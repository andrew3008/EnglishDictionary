package space.br1440.platform.tracing.autoconfigure.integrations.async;

import io.micrometer.context.ContextSnapshotFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

/**
 * Авто-конфигурация переноса OTel Context / MDC между потоками {@code @Async} /
 * {@code ThreadPoolTaskExecutor}.
 * <p>
 * Активируется ТОЛЬКО при явном включении
 * {@code platform.tracing.thread-pool-task-executor.enabled=true}.
 * По умолчанию выключена,
 * чтобы не вмешиваться в работу прикладных executors и не конфликтовать с Spring Boot 3.5
 * {@code ContextPropagatingTaskDecorator}, кастомными {@code TaskDecorator}, security/tenant
 * propagation, OpenTelemetry Java Agent executor instrumentation.
 * <p>
 * При активации:
 * <ol>
 *   <li>Регистрируется {@link ContextSnapshotFactory} (если ещё не в контексте) — единый
 *       источник снимков контекста на базе Micrometer Context Propagation;</li>
 *   <li>Регистрируется {@link ThreadPoolTaskExecutorContextPropagationBeanPostProcessor} —
 *       композирует существующие {@code TaskDecorator}'ы с
 *       {@link PlatformContextTaskDecorator}, оставляя платформенный самым внешним слоем
 *       (см. {@link PlatformContextTaskDecorator} Javadoc).</li>
 * </ol>
 */
@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
@ConditionalOnClass({ThreadPoolTaskExecutor.class, ContextSnapshotFactory.class})
@ConditionalOnProperty(
        prefix = TracingProperties.PREFIX + ".thread-pool-task-executor",
        name = "enabled",
        havingValue = "true")
public class TracingAsyncContextAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TracingAsyncContextAutoConfiguration.class);

    /**
     * Глобальный {@link ContextSnapshotFactory}. {@link ConditionalOnMissingBean} позволяет
     * прикладному коду или иным авто-конфигурациям зарегистрировать собственный экземпляр
     * (например, с кастомными {@code ContextRegistry}).
     */
    @Bean
    @ConditionalOnMissingBean
    public ContextSnapshotFactory platformContextSnapshotFactory() {
        return ContextSnapshotFactory.builder().build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ThreadPoolTaskExecutorContextPropagationBeanPostProcessor
            platformThreadPoolTaskExecutorContextPropagationBeanPostProcessor(
                    ContextSnapshotFactory contextSnapshotFactory) {

        log.info("Platform TaskDecorator activated: composition with existing "
                + "ThreadPoolTaskExecutor TaskDecorators");

        return new ThreadPoolTaskExecutorContextPropagationBeanPostProcessor(contextSnapshotFactory);
    }
}
