package space.br1440.platform.tracing.core.mdc.remote;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.RemoteServiceNameSource;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Иммутабельный read-chain для логического имени upstream-сервиса.
 * <p>
 * Порядок разрешения:
 * <ol>
 *   <li>ThreadLocal MDC ({@link TracingMdcKeys#REMOTE_SERVICE}) — приоритет highest,
 *       уже установлен synchronously до вызова error-handling.</li>
 *   <li>Каждый зарегистрированный {@link RemoteServiceNameSource} в порядке {@code @Order}.</li>
 *   <li>Встроенный fallback через {@link RemoteServiceTraceMirror} по {@code traceId} —
 *       всегда последний шаг (WebFlux async scenario).</li>
 * </ol>
 *
 * <p>Конструируется autoconfigure-модулем из
 * {@code ObjectProvider<RemoteServiceNameSource>.orderedStream()}.
 *
 * @see RemoteServiceNameSource
 * @see RemoteServiceMdc
 */
public final class RemoteServiceNameResolver {

    private final List<RemoteServiceNameSource> sources;

    /**
     * @param sources упорядоченный список contributors (порядок определяется {@code @Order})
     */
    public RemoteServiceNameResolver(@Nonnull List<RemoteServiceNameSource> sources) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }

    /**
     * Resolves без traceId-fallback (Servlet / синхронные контексты).
     */
    @Nonnull
    public Optional<String> resolve() {
        return resolve(null);
    }

    /**
     * Resolves с trace-scoped mirror fallback (WebFlux async scenario).
     *
     * @param traceId идентификатор текущего trace; {@code null} отключает mirror-fallback
     */
    @Nonnull
    public Optional<String> resolve(@Nullable String traceId) {
        // 1. ThreadLocal MDC — highest priority
        String mdc = MDC.get(TracingMdcKeys.REMOTE_SERVICE);
        if (mdc != null && !mdc.isBlank()) {
            return Optional.of(mdc);
        }

        // 2. Contributed sources in @Order
        for (RemoteServiceNameSource source : sources) {
            try {
                Optional<String> value = source.resolve();
                if (value != null && value.isPresent() && !value.get().isBlank()) {
                    return value;
                }
            } catch (RuntimeException ignored) {
                // source fail-soft: не должен ломать error-handling
            }
        }

        // 3. Built-in mirror fallback (WebFlux: CLIENT span ends on different thread)
        return RemoteServiceTraceMirror.get(traceId);
    }
}
