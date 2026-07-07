package space.br1440.platform.tracing.otel.extension.resource;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.autoconfigure.spi.internal.ConditionalResourceProvider;
import io.opentelemetry.sdk.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.extension.safety.PlatformThrowables;
import space.br1440.platform.tracing.otel.extension.safety.TracingDiagnostics;

import java.util.Objects;

/**
 * Safe-обёртка вокруг {@link PlatformResourceProvider} (Фаза 11).
 * <p>
 * Resource-провайдер выполняется один раз на старте SDK. Непредвиденный сбой в логике резолвинга
 * (например, баг в детекторе host/container или в чтении манифеста) не должен ронять инициализацию
 * tracing и приложение. Обёртка ловит непредвиденные {@link Throwable}, фиксирует их в
 * {@link TracingDiagnostics} и возвращает безопасный fallback (пустой Resource), позволяя SDK
 * продолжить старт (паттерн Micrometer noop-on-failure).
 *
 * <h2>STRICT fail-fast сохраняется</h2>
 * Контракт §8 (STRICT) намеренно роняет старт при неполной resource-идентичности (dev/CI).
 * Поэтому в режиме {@link ResourceValidationMode#STRICT} обёртка <b>пробрасывает</b> исключение
 * как есть — не подменяя осознанный fail-fast на «тихий» fallback. В LENIENT/default непредвиденные
 * сбои изолируются.
 *
 * <p>Регистрируется как SPI вместо {@link PlatformResourceProvider} (см.
 * {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider}).
 */
public final class SafeResourceProvider implements ResourceProvider, ConditionalResourceProvider {

    private static final Logger log = LoggerFactory.getLogger(SafeResourceProvider.class);

    private final PlatformResourceProvider delegate;
    private final TracingDiagnostics diagnostics;

    /** Конструктор для SPI: оборачивает стандартный платформенный провайдер. */
    public SafeResourceProvider() {
        this(new PlatformResourceProvider(), TracingDiagnostics.shared());
    }

    SafeResourceProvider(PlatformResourceProvider delegate, TracingDiagnostics diagnostics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public boolean shouldApply(ConfigProperties config, Resource existing) {
        try {
            return delegate.shouldApply(config, existing);
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            diagnostics.recordResourceFailure();
            log.warn("SafeResourceProvider: shouldApply упал ({}) — провайдер не применяется", ex.toString());
            return false;
        }
    }

    @Override
    public Resource createResource(ConfigProperties config) {
        try {
            return delegate.createResource(config);
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            // STRICT: осознанный fail-fast §8 — пробрасываем как есть (dev/CI ожидают падение старта).
            if (isStrictMode(config)) {
                throw ex instanceof RuntimeException re ? re : new IllegalStateException(ex);
            }
            // LENIENT/default: непредвиденный сбой изолируем, возвращаем пустой Resource + метрика.
            diagnostics.recordResourceFailure();
            log.warn("SafeResourceProvider: createResource упал ({}) — fallback на пустой Resource", ex.toString());
            return Resource.empty();
        }
    }

    private static boolean isStrictMode(ConfigProperties config) {
        ResourceValidationMode mode = ResourceValidationMode.fromConfig(
                config.getString(ExtensionPropertyNames.RESOURCE_VALIDATION_MODE));
        return mode == ResourceValidationMode.STRICT;
    }

    @Override
    public int order() {
        // Сохраняем порядок делегата (дополняем, не перетираем явный OTEL_*).
        return delegate.order();
    }
}
