package space.br1440.platform.tracing.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import space.br1440.platform.tracing.api.mdc.RemoteServiceNameSource;
import space.br1440.platform.tracing.autoconfigure.servicename.PlatformLocalServiceNameProvider;
import space.br1440.platform.tracing.autoconfigure.servicename.PlatformRemoteServiceNameProvider;
import space.br1440.platform.tracing.core.mdc.remote.RemoteServiceNameResolver;

/**
 * Авто-конфигурация платформенных провайдеров имени сервиса для интеграции с внешним
 * errorhandling-стартером ({@code web-error-model} + {@code @ControllerAdvice}).
 * <p>
 * Регистрирует два бина <b>всегда</b> при наличии модуля на classpath:
 * без {@code @ConditionalOnProperty}, без {@code @ConditionalOnBean}. Это сделано осознанно —
 * по тому же контракту, что и {@code RequestContextSupplierAutoConfiguration}: внешний
 * errorhandling-стартер потребителя должен получать корректные значения независимо от
 * состояния {@code platform.tracing.enabled}.
 * <p>
 * Поведение при {@code platform.tracing.enabled=false}:
 * <ul>
 *   <li>{@link PlatformLocalServiceNameProvider} продолжает работать корректно: возвращает
 *       {@code platform.tracing.service.name} → {@code spring.application.name} →
 *       {@link PlatformLocalServiceNameProvider#UNKNOWN_SERVICE}. Все источники доступны
 *       через {@link Environment} и не требуют активной OTel-инфраструктуры.</li>
 *   <li>{@link PlatformRemoteServiceNameProvider} возвращает {@link java.util.Optional#empty()}:
 *       при отключённом tracing'е CLIENT-span'ы не создаются и атрибут upstream-сервиса
 *       не выставляется — это и есть ожидаемое поведение.</li>
 * </ul>
 *
 * <h2>Внимание разработчику</h2>
 * <p><b>Намеренно не имеет зависимостей от tracing-core бинов:</b> атрибут
 * {@code after = TracingCoreAutoConfiguration.class} не указан осознанно. Оба провайдера
 * читают данные из {@link Environment} и/или {@link io.opentelemetry.api.trace.Span#current()} +
 * MDC, а не из {@link space.br1440.platform.tracing.api.TraceOperations}. <b>Не добавлять</b>
 * {@code after}/{@code before} «по аналогии с соседними классами»: это сделает регистрацию
 * провайдеров зависимой от {@code platform.tracing.enabled} и сломает контракт «errorhandling
 * всегда получает корректные имена сервисов, даже при выключенном tracing».
 */
@AutoConfiguration
@EnableConfigurationProperties(TracingProperties.class)
public class ServiceNameProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PlatformLocalServiceNameProvider.class)
    public PlatformLocalServiceNameProvider platformLocalServiceNameProvider(TracingProperties properties,
                                                                             Environment environment) {
        return new PlatformLocalServiceNameProvider(properties, environment);
    }

    /**
     * Регистрирует {@link PlatformRemoteServiceNameProvider} с полностью составленным
     * {@link RemoteServiceNameResolver}.
     * <p>
     * Contributors ({@link RemoteServiceNameSource} beans) собираются через
     * {@link ObjectProvider} — лениво и без обязательного наличия: если ни одного бина
     * {@code RemoteServiceNameSource} в контексте нет, resolver работает с пустым списком
     * (MDC + mirror-fallback достаточны для типичного сценария).
     * <p>
     * Потребители платформы могут расширить цепочку, зарегистрировав бин
     * {@code RemoteServiceNameSource} — он будет подхвачен автоматически.
     * <p>
     * <b>Ограничение:</b> список contributors фиксируется при старте контекста
     * ({@code contributors.orderedStream().toList()}); runtime-расширение через
     * {@code @RefreshScope} или динамическую регистрацию бинов не поддерживается.
     */
    @Bean
    @ConditionalOnMissingBean(PlatformRemoteServiceNameProvider.class)
    public PlatformRemoteServiceNameProvider platformRemoteServiceNameProvider(
            ObjectProvider<RemoteServiceNameSource> contributors) {
        RemoteServiceNameResolver resolver = new RemoteServiceNameResolver(
                contributors.orderedStream().toList());
        return new PlatformRemoteServiceNameProvider(resolver);
    }
}
