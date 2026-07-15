package space.br1440.platform.tracing.autoconfigure.servicename;

import space.br1440.platform.tracing.core.mdc.remote.RemoteServiceNameResolver;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Поставщик логического имени upstream-сервиса, вызов которого завершился ошибкой
 * в рамках текущего запроса.
 * <p>
 * Назначение: внешний errorhandling-стартер потребителя инжектирует
 * {@code Supplier<Optional<String>> platformRemoteServiceNameProvider} и использует его
 * значение для заполнения поля {@code domain} cause-уровневого DTO ошибки
 * ({@code space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO#domain}).
 * <p>
 * Источник: {@link RemoteServiceNameResolver} (MDC → contributed sources → trace-scoped mirror).
 * Запись MDC выполняется {@link space.br1440.platform.tracing.core.mdc.remote.RemoteServiceMdc}
 * из {@code EnrichingSpanProcessor} при завершении ERROR'ного CLIENT-span'а.
 * <p>
 * PR-2: constructor injection {@code RemoteServiceNameResolver} bean с {@code ObjectProvider} contributors.
 * <p>
 * <b>Контракт §37 (non-blocking):</b> метод {@link #get()} никогда не выбрасывает исключений
 * и не выполняет блокирующих операций.
 */
public final class PlatformRemoteServiceNameProvider implements Supplier<Optional<String>> {

    private final RemoteServiceNameResolver resolver;

    public PlatformRemoteServiceNameProvider() {
        this(new RemoteServiceNameResolver(List.of()));
    }

    PlatformRemoteServiceNameProvider(RemoteServiceNameResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Optional<String> get() {
        return resolver.resolve();
    }
}
