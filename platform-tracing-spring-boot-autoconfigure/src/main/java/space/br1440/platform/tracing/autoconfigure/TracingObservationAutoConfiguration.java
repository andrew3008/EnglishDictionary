package space.br1440.platform.tracing.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import space.br1440.platform.tracing.autoconfigure.actuator.DropOldestAspirationDiagnostics;
import space.br1440.platform.tracing.autoconfigure.actuator.DualChannelDriftDiagnostics;

/**
 * Стек-нейтральная авто-конфигурация Observation-аспектов платформенной трассировки.
 * <p>
 * Содержит только не зависящие от веб-стека бины. Серверные и клиентские
 * {@code ObservationConvention}'ы вынесены в стек-специфичные авто-конфигурации:
 * <ul>
 *   <li>{@code WebMvcSuppressMicrometerTracingAutoConfiguration},
 *       {@code PlatformClientRequestObservationConvention},
 *       {@code PlatformServerRequestObservationConvention} — модуль
 *       {@code platform-tracing-autoconfigure-webmvc};</li>
 *   <li>{@code WebFluxSuppressMicrometerTracingAutoConfiguration},
 *       {@code PlatformReactiveClientRequestObservationConvention},
 *       {@code PlatformReactiveServerRequestObservationConvention} — модуль
 *       {@code platform-tracing-autoconfigure-webflux}.</li>
 * </ul>
 * <p>
 * Здесь регистрируется только стартовый {@link TracingObservationSuppressStartupRunner},
 * который выводит WARN-сообщение при несогласованной конфигурации
 * {@code suppress-micrometer-tracing} и присутствия OpenTelemetry Java Agent.
 */
@AutoConfiguration
@AutoConfigureAfter(TracingCoreAutoConfiguration.class)
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingObservationAutoConfiguration {

    /**
     * Регистрирует стартовый WARN-сигнал по матрице 2×2 (suppress × agent). См. Javadoc
     * {@link TracingObservationSuppressStartupRunner} для описания сценариев.
     */
    @Bean
    public TracingObservationSuppressStartupRunner platformTracingObservationSuppressStartupRunner(
            TracingProperties properties) {
        return TracingObservationSuppressStartupRunner.create(properties.getSuppression().isSuppressMicrometerTracing());
    }

    /**
     * Регистрирует startup-диагностику dual-channel расхождения Spring vs OTel agent.
     * См. Javadoc {@link DualChannelDriftDiagnostics} — WARN diagnostic-only, не misconfiguration.
     */
    @Bean
    public DualChannelDriftDiagnostics platformDualChannelDriftDiagnostics(TracingProperties properties) {
        return new DualChannelDriftDiagnostics(
                properties,
                properties.getDiagnostics().isDualChannelWarn()
        );
    }

    /**
     * Регистрирует startup-диагностику aspiration-расхождения политики переполнения очереди:
     * Spring {@code queue.policy=DROP_OLDEST} vs Agent {@code overflow-policy}. WARN только при
     * explicit override Spring-side; в default-конфигурации — actuator-snapshot + опциональный
     * INFO-лог. См. Javadoc {@link DropOldestAspirationDiagnostics} и
     * {@code docs/decisions/ADR-drop-oldest-export-processor-v1.md}.
     */
    @Bean
    public DropOldestAspirationDiagnostics platformDropOldestAspirationDiagnostics(
            TracingProperties properties,
            ConfigurableEnvironment environment) {
        return new DropOldestAspirationDiagnostics(
                properties,
                environment,
                properties.getDiagnostics().isDropOldestAspirationWarn(),
                properties.getDiagnostics().isDropOldestAspirationInfo()
        );
    }
}
