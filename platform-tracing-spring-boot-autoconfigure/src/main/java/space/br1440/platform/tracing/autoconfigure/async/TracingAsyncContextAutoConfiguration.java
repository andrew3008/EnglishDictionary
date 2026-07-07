package space.br1440.platform.tracing.autoconfigure.async;

import io.micrometer.context.ContextSnapshotFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

/**
 * Авто-конфигурация переноса OTel Context / MDC между потоками {@code @Async} /
 * {@code ThreadPoolTaskExecutor}.
 * <p>
 * Активируется ТОЛЬКО при явном включении
 * {@code platform.tracing.context-propagation.async.enabled=true}. По умолчанию выключена,
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
 * <p>
 * <b>Mode validation.</b> При неизвестном значении
 * {@code platform.tracing.context-propagation.async.mode} логируется WARN и используется
 * fallback {@code propagate-current-context} (forward-compatibility).
 */
@AutoConfiguration
@AutoConfigureAfter(TracingCoreAutoConfiguration.class)
@ConditionalOnClass({ThreadPoolTaskExecutor.class, ContextSnapshotFactory.class})
@ConditionalOnProperty(
        prefix = TracingProperties.PREFIX + ".context-propagation.async",
        name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties(TracingProperties.class)
public class TracingAsyncContextAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TracingAsyncContextAutoConfiguration.class);

    /** Поддерживаемый в v0.1.0 режим переноса контекста. */
    public static final String MODE_PROPAGATE_CURRENT_CONTEXT = "propagate-current-context";

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
                    ContextSnapshotFactory contextSnapshotFactory,
                    TracingProperties tracingProperties) {

        String mode = tracingProperties.getContextPropagation().getAsync().getMode();
        if (mode == null || !mode.equals(MODE_PROPAGATE_CURRENT_CONTEXT)) {
            log.warn("Неизвестное значение platform.tracing.context-propagation.async.mode='{}'; "
                    + "будет использован fallback '{}'", mode, MODE_PROPAGATE_CURRENT_CONTEXT);
        }

        log.info("Платформенный TaskDecorator активирован: режим '{}', composition с существующими "
                + "TaskDecorator-ами ThreadPoolTaskExecutor", MODE_PROPAGATE_CURRENT_CONTEXT);

        return new ThreadPoolTaskExecutorContextPropagationBeanPostProcessor(contextSnapshotFactory);
    }
}
