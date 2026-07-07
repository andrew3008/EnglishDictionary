package space.br1440.platform.tracing.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.tracing.api.context.TracingRequestContext;
import space.br1440.platform.tracing.autoconfigure.errorhandling.TracingRequestContextSupplier;

import java.util.function.Supplier;

/**
 * Авто-конфигурация поставщика {@link TracingRequestContext} для платформенного error-handling.
 * <p>
 * Регистрирует bean {@code Supplier<TracingRequestContext>} с именем
 * {@code platformTracingRequestContextSupplier} <b>всегда</b> при наличии модуля на classpath:
 * без property-gating, без {@code @ConditionalOnBean}, без {@code @ConditionalOnClass} на tracing-core.
 * Это сделано осознанно, чтобы модуль трассировки не тянул {@code web-error-model}, а ошибка по-прежнему
 * обогащалась корреляцией при {@code platform.tracing.enabled=false}.
 * <p>
 * Маппинг в {@code space.br1440.platform.errorhandling.model.RequestContext} выполняется
 * отдельной авто-конфигурацией {@code error-handling-core} (см. документацию к модулю).
 * <p>
 * При {@code platform.tracing.enabled=false} bean {@link TracingRequestContextSupplier} продолжает
 * работать корректно: {@code Span.current()} вернёт {@code Span.getInvalid()}, supplier отдаст
 * {@link TracingRequestContext} с пустыми trace/span и {@code correlationId} из MDC — ожидаемое
 * поведение при отключённой трассировке.
 *
 * <h2>Внимание разработчику</h2>
 * <p><b>Намеренно не имеет зависимостей от tracing-core бинов:</b>
 * атрибут {@code after = TracingCoreAutoConfiguration.class} не указан осознанно —
 * {@link TracingRequestContextSupplier} не требует {@link space.br1440.platform.tracing.api.PlatformTracing}
 * и работает напрямую с {@code Span.current()} и {@link org.slf4j.MDC}. <b>Не добавлять</b>
 * {@code after}/{@code before} «по аналогии с соседними классами»: это сделает регистрацию
 * supplier'а зависимой от {@code platform.tracing.enabled} и сломает контракт «errorhandling
 * всегда получает снимок контекста, даже при выключенном tracing».
 */
@AutoConfiguration
public class RequestContextSupplierAutoConfiguration {

    @Bean(name = "platformTracingRequestContextSupplier")
    @ConditionalOnMissingBean(name = "platformTracingRequestContextSupplier")
    public Supplier<TracingRequestContext> platformTracingRequestContextSupplier() {
        return new TracingRequestContextSupplier();
    }
}
