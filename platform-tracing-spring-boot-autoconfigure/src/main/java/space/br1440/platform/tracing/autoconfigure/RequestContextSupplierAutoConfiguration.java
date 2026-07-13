package space.br1440.platform.tracing.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.tracing.api.context.RequestTraceContextSnapshot;
import space.br1440.platform.tracing.autoconfigure.errorhandling.RequestTraceContextSnapshotSupplier;

import java.util.function.Supplier;

/**
 * Авто-конфигурация поставщика {@link RequestTraceContextSnapshot} для платформенного error-handling.
 * <p>
 * Регистрирует bean {@code Supplier<RequestTraceContextSnapshot>} с именем
 * {@code platformRequestTraceContextSnapshotSupplier} <b>всегда</b> при наличии модуля на classpath:
 * без property-gating, без {@code @ConditionalOnBean}, без {@code @ConditionalOnClass} на tracing-core.
 * Это сделано осознанно, чтобы модуль трассировки не тянул {@code web-error-model}, а ошибка по-прежнему
 * обогащалась корреляцией при {@code platform.tracing.enabled=false}.
 * <p>
 * Маппинг в {@code space.br1440.platform.errorhandling.model.RequestContext} выполняется
 * отдельной авто-конфигурацией {@code error-handling-core} (см. документацию к модулю).
 * <p>
 * При {@code platform.tracing.enabled=false} bean {@link RequestTraceContextSnapshotSupplier} продолжает
 * работать корректно: {@code Span.current()} вернёт {@code Span.getInvalid()}, supplier отдаст
 * {@link RequestTraceContextSnapshot} с пустыми trace/span и {@code correlationId} из MDC — ожидаемое
 * поведение при отключённой трассировке.
 *
 * <h2>Внимание разработчику</h2>
 * <p><b>Намеренно не имеет зависимостей от tracing-core бинов:</b>
 * атрибут {@code after = TracingCoreAutoConfiguration.class} не указан осознанно —
 * {@link RequestTraceContextSnapshotSupplier} не требует {@link space.br1440.platform.tracing.api.TraceOperations}
 * и работает напрямую с {@code Span.current()} и {@link org.slf4j.MDC}. <b>Не добавлять</b>
 * {@code after}/{@code before} «по аналогии с соседними классами»: это сделает регистрацию
 * supplier'а зависимой от {@code platform.tracing.enabled} и сломает контракт «errorhandling
 * всегда получает снимок контекста, даже при выключенном tracing».
 */
@AutoConfiguration
public class RequestContextSupplierAutoConfiguration {

    @Bean(name = "platformRequestTraceContextSnapshotSupplier")
    @ConditionalOnMissingBean(name = "platformRequestTraceContextSnapshotSupplier")
    public Supplier<RequestTraceContextSnapshot> platformRequestTraceContextSnapshotSupplier() {
        return new RequestTraceContextSnapshotSupplier();
    }
}
