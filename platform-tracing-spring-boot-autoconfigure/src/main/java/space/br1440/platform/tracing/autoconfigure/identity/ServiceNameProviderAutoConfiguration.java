package space.br1440.platform.tracing.autoconfigure.identity;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import space.br1440.platform.tracing.api.mdc.RemoteServiceNameSource;
import space.br1440.platform.tracing.autoconfigure.identity.PlatformLocalServiceNameProvider;
import space.br1440.platform.tracing.autoconfigure.identity.PlatformRemoteServiceNameProvider;
import space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceNameResolver;

import java.util.List;

/**
 * Авто-конфигурация платформенных провайдеров имени сервиса для интеграции с внешним
 * errorhandling-стартером ({@code web-error-model} + {@code @ControllerAdvice}).
 * <p>
 * Регистрирует два бина <b>всегда</b> при наличии модуля на classpath:
 * без {@code @ConditionalOnProperty}, без {@code @ConditionalOnBean}. Это сделано осознанно —
 * по тому же контракту, что и {@code RequestContextSupplierAutoConfiguration}: внешний
 * errorhandling-стартер потребителя должен получать корректные значения независимо от
 * режима {@code platform.tracing.enabled}.
 * <p>
 * Поведение при {@code platform.tracing.enabled=false}:
 * <ul>
 *   <li>{@link PlatformLocalServiceNameProvider} продолжает работать корректно: возвращает
 *       {@code spring.application.name} → {@link PlatformLocalServiceNameProvider#UNKNOWN_SERVICE}.
 *       Источник доступен через {@link Environment} и не требует активной OTel-инфраструктуры.</li>
 *   <li>{@link PlatformRemoteServiceNameProvider} возвращает {@link java.util.Optional#empty()}:
 *       при отключённом tracing'е CLIENT-span'ы не создаются и атрибут upstream-сервиса
 *       не выставляется — это и есть ожидаемое поведение.</li>
 * </ul>
 * <p>
 * <b>Намеренно не имеет зависимостей от tracing-core бинов:</b> атрибут
 * {@code after = TracingCoreAutoConfiguration.class} не указан осознанно. Оба провайдера
 * читают данные из {@link Environment} и/или {@link io.opentelemetry.api.trace.Span#current()} +
 * MDC, а не из {@link space.br1440.platform.tracing.api.TraceOperations}. <b>Не добавлять</b>
 * {@code after}/{@code before} «по аналогии с соседними классами»: это сделает регистрацию
 * провайдеров зависимой от {@code platform.tracing.enabled} и сломает контракт «errorhandling
 * всегда получает корректные имена сервисов, даже при выключенном tracing».
 */
@AutoConfiguration
public class ServiceNameProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PlatformLocalServiceNameProvider.class)
    public PlatformLocalServiceNameProvider platformLocalServiceNameProvider(Environment environment) {
        return new PlatformLocalServiceNameProvider(environment);
    }

    @Bean
    @ConditionalOnMissingBean(PlatformRemoteServiceNameProvider.class)
    public PlatformRemoteServiceNameProvider platformRemoteServiceNameProvider(ObjectProvider<RemoteServiceNameSource> contributors) {
        List<RemoteServiceNameSource> sources = contributors.orderedStream().toList();
        RemoteServiceNameResolver resolver = new RemoteServiceNameResolver(sources);
        return new PlatformRemoteServiceNameProvider(resolver);
    }
}
