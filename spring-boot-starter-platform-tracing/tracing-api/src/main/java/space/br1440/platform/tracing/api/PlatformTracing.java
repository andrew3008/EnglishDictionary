package space.br1440.platform.tracing.api;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanScope;

import java.util.Optional;

/**
 * Платформенный фасад трассировки.
 * <p>
 * Является единственной публичной точкой входа для прикладного кода, желающего вручную создавать и обогащать
 * span'ы. Реализация поверх Micrometer Observation API и OpenTelemetry API находится в модуле
 * {@code platform-tracing-core}, бин подключается автоматически через {@code platform-tracing-spring-boot-autoconfigure}.
 * <p>
 * Типизированные методы создания span'ов соответствуют требуемым категориям из платформенного
 * перечня обязательных типов: HTTP server / HTTP client / database / RPC / internal.
 * <p>
 * Методы получения {@code traceId} и {@code spanId} предназначены для интеграции со сторонними подсистемами
 * (логирование через MDC, формирование заголовков ответа, обогащение моделей ошибок).
 */
public interface PlatformTracing {

    /**
     * Возвращает идентификатор активного trace'а в шестнадцатеричной форме (32 символа), если контекст активен.
     */
    @Nonnull
    Optional<String> currentTraceId();

    /**
     * Возвращает идентификатор активного span'а в шестнадцатеричной форме (16 символов), если контекст активен.
     */
    @Nonnull
    Optional<String> currentSpanId();

    /**
     * Создаёт span произвольной категории. Используется только в случаях, когда стандартные типизированные
     * методы не подходят. Для штатных сценариев предпочтительны {@link #startHttpServer},
     * {@link #startHttpClient}, {@link #startDb}, {@link #startRpc}, {@link #startInternal}.
     */
    @Nonnull
    SpanScope startSpan(@Nonnull String name, @Nonnull SpanCategory category);

    /**
     * Создаёт серверный HTTP span (входящий запрос).
     */
    @Nonnull
    default SpanScope startHttpServer(@Nonnull String name) {
        return startSpan(name, SpanCategory.HTTP_SERVER);
    }

    /**
     * Создаёт клиентский HTTP span (исходящий запрос).
     */
    @Nonnull
    default SpanScope startHttpClient(@Nonnull String name) {
        return startSpan(name, SpanCategory.HTTP_CLIENT);
    }

    /**
     * Создаёт span операции с базой данных.
     */
    @Nonnull
    default SpanScope startDb(@Nonnull String name) {
        return startSpan(name, SpanCategory.DATABASE);
    }

    /**
     * Создаёт span RPC-операции (gRPC, brokered).
     */
    @Nonnull
    default SpanScope startRpc(@Nonnull String name) {
        return startSpan(name, SpanCategory.RPC);
    }

    /**
     * Создаёт внутренний span (бизнес-операция, не пересекающая сетевую границу).
     */
    @Nonnull
    default SpanScope startInternal(@Nonnull String name) {
        return startSpan(name, SpanCategory.INTERNAL);
    }

    /**
     * Регистрирует исключение на текущем активном span'е. Если активного span'а нет, операция не выполняется.
     */
    void recordException(@Nullable Throwable throwable);
}
