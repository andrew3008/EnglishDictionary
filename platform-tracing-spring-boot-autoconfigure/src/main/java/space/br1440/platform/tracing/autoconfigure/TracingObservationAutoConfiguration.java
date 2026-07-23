package space.br1440.platform.tracing.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Стек-нейтральная авто-конфигурация Observation-аспектов платформенной трассировки.
 * <p>
 * Содержит только не зависящие от веб-стека бины. Серверные и клиентские
 * {@code ObservationConvention}'ы вынесены в стек-специфичные авто-конфигурации.
 */
@AutoConfiguration
@AutoConfigureAfter(TracingCoreAutoConfiguration.class)
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingObservationAutoConfiguration {

    /**
     * Регистрирует стартовый WARN-сигнал по матрице 2×2 (suppress × agent).
     */
    @Bean
    public TracingObservationSuppressStartupRunner platformTracingObservationSuppressStartupRunner(
            TracingProperties properties) {
        return TracingObservationSuppressStartupRunner.create(properties.getSuppression().isSuppressMicrometerTracing());
    }
}
