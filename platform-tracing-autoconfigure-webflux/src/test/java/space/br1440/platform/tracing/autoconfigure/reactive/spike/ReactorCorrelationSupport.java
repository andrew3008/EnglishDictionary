package space.br1440.platform.tracing.autoconfigure.reactive.spike;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.Optional;

/**
 * SPIKE (CP-1 §21 R2-C3) — НЕ production код, test-sources.
 *
 * <p>Минимальная реализация reactive correlation-механизма, доказывающая семантику:
 * <ul>
 *   <li>значение хранится как <b>immutable String</b> в Reactor {@link Context} (ключ {@link #KEY});
 *       OTel {@code Scope} через async-границу <b>не</b> переносится;</li>
 *   <li>чтение downstream — через {@link ContextView} ({@code deferContextual});</li>
 *   <li>проекция в ThreadLocal/MDC на смене scheduler'а — через Micrometer
 *       {@link ThreadLocalAccessor} + Reactor automatic context propagation
 *       (паттерн {@code RemoteServiceContextPropagation}).</li>
 * </ul>
 */
public final class ReactorCorrelationSupport {

    /** Canonical Reactor Context / MDC ключ корреляции. */
    public static final String KEY = "platform.correlation.id";

    private static final ThreadLocal<String> CORRELATION_TL = new ThreadLocal<>();

    private static volatile boolean accessorRegistered;

    private ReactorCorrelationSupport() {
    }

    /** Оператор привязки: пишет immutable String в Reactor Context для всего поддерева подписки. */
    public static Context write(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return Context.empty();
        }
        return Context.of(KEY, correlationId);
    }

    /** Контекстное чтение из Reactor Context (thread-независимое). */
    public static Optional<String> read(ContextView view) {
        if (view == null || !view.hasKey(KEY)) {
            return Optional.empty();
        }
        Object value = view.get(KEY);
        return (value instanceof String s && !s.isBlank()) ? Optional.of(s) : Optional.empty();
    }

    /** Чтение из ThreadLocal (восстанавливается Micrometer-мостом на scheduler hop'ах). */
    public static Optional<String> readThreadLocal() {
        String v = CORRELATION_TL.get();
        return (v != null && !v.isBlank()) ? Optional.of(v) : Optional.empty();
    }

    /** Idempotent-регистрация ThreadLocalAccessor (проекция correlationId → ThreadLocal + MDC). */
    public static void registerAccessorIfAbsent() {
        if (accessorRegistered) {
            return;
        }
        synchronized (ReactorCorrelationSupport.class) {
            if (accessorRegistered) {
                return;
            }
            ContextRegistry.getInstance().registerThreadLocalAccessor(new CorrelationAccessor());
            accessorRegistered = true;
        }
    }

    /** Реализация операций поверх Reactor Context. */
    public static ReactiveCorrelationOperations operations() {
        return new ReactorCorrelationOperations();
    }

    private static final class ReactorCorrelationOperations implements ReactiveCorrelationOperations {

        @Override
        public <T> Mono<T> withCorrelationId(String correlationId, Mono<T> execution) {
            return execution.contextWrite(ctx -> ctx.putAll(write(correlationId).readOnly()));
        }

        @Override
        public <T> Flux<T> withCorrelationId(String correlationId, Flux<T> execution) {
            return execution.contextWrite(ctx -> ctx.putAll(write(correlationId).readOnly()));
        }
    }

    private static final class CorrelationAccessor implements ThreadLocalAccessor<String> {

        @Override
        public Object key() {
            return KEY;
        }

        @Override
        public String getValue() {
            return CORRELATION_TL.get();
        }

        @Override
        public void setValue(String value) {
            if (value == null) {
                CORRELATION_TL.remove();
                MDC.remove(KEY);
            } else {
                CORRELATION_TL.set(value);
                MDC.put(KEY, value);
            }
        }

        @Override
        public void reset() {
            CORRELATION_TL.remove();
            MDC.remove(KEY);
        }
    }
}
