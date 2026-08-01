package space.br1440.platform.tracing.autoconfigure.integrations.observation;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;

/**
 * Стек-нейтральная авто-конфигурация Observation-аспектов платформенной трассировки.
 * <p>
 * Содержит только не зависящие от веб-стека бины. Серверные и клиентские
 * {@code ObservationConvention}'ы вынесены в стек-специфичные авто-конфигурации.
 */
@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.MODE, havingValue = "AGENT", matchIfMissing = true)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingObservationAutoConfiguration {

    /**
     * Регистрирует стартовый WARN-сигнал по матрице 2×2 (эффективный suppress × agent).
     * <p>
     * Эффективное значение читается через {@link MicrometerTracingSuppressionResolver} — тот же
     * resolver, который решает активацию {@code WebMvc}/{@code WebFluxSuppressMicrometerTracingAutoConfiguration}.
     * Поэтому WARN срабатывает только при явном оператор-override, конфликтующем с фактическим
     * присутствием Agent'а; в auto-derive режиме эффективное значение по построению совпадает с
     * присутствием Agent'а и предупреждение не требуется.
     */
    @Bean
    public TracingObservationSuppressStartupRunner platformTracingObservationSuppressStartupRunner(
            Environment environment) {
        return TracingObservationSuppressStartupRunner.create(
                MicrometerTracingSuppressionResolver.resolve(environment));
    }
}
