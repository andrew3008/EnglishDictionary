package space.br1440.platform.tracing.otel.extension.propagation;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.otel.extension.safety.PlatformThrowables;
import space.br1440.platform.tracing.otel.extension.safety.RateLimitedLogger;
import space.br1440.platform.tracing.otel.extension.safety.TracingDiagnostics;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Safe-обёртка вокруг платформенного {@link TextMapPropagator} (Фаза 11).
 * <p>
 * {@code extract}/{@code inject} вызываются на hot-path входящих/исходящих запросов
 * (HTTP, Kafka) на application thread. Падение платформенного propagator'а (например,
 * {@link PlatformTraceControlPropagator} на кривом carrier'е или {@link FilteringBaggagePropagator}
 * на некорректном baggage) не должно прерывать бизнес-вызов и не должно ломать сквозную
 * передачу W3C trace context.
 *
 * <h2>Контракт</h2>
 * <ul>
 *   <li><b>{@code extract}</b>: при любом сбое возвращается <b>исходный переданный {@code context}</b>
 *       (а НЕ {@link Context#root()}). Это критично: в составном propagator'е W3C/B3 trace context
 *       мог быть уже успешно извлечён предыдущими звеньями цепочки — мы не имеем права его потерять;</li>
 *   <li><b>{@code inject}</b>: при сбое заголовки не пишутся (не инжектим небезопасные/частичные
 *       заголовки), бизнес-вызов продолжается;</li>
 *   <li><b>{@code fields}</b>: при сбое возвращается пустой список.</li>
 * </ul>
 *
 * <p>Best Practice (Фаза 11.md #8): не отключать propagation при сбое платформенного слоя —
 * сервис может временно не применять платформенные сигналы, но обязан продолжать передавать
 * стандартный trace context downstream.
 */
public final class SafeTextMapPropagator implements TextMapPropagator {

    private final TextMapPropagator delegate;
    private final TracingDiagnostics diagnostics;
    private final RateLimitedLogger log;

    public SafeTextMapPropagator(TextMapPropagator delegate) {
        this(delegate, TracingDiagnostics.shared());
    }

    public SafeTextMapPropagator(TextMapPropagator delegate, TracingDiagnostics diagnostics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.log = new RateLimitedLogger(LoggerFactory.getLogger(SafeTextMapPropagator.class));
    }

    @Override
    public Collection<String> fields() {
        try {
            Collection<String> fields = delegate.fields();
            return fields != null ? fields : List.of();
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            diagnostics.recordPropagatorFailure();
            log.warn("SafeTextMapPropagator: fields() упал ({})", ex.toString());
            return List.of();
        }
    }

    @Override
    public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
        try {
            Context extracted = delegate.extract(context, carrier, getter);
            // Если делегат вернул null — не теряем уже извлечённый вышестоящими propagator'ами контекст.
            return extracted != null ? extracted : context;
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            diagnostics.recordPropagatorFailure();
            log.warn("SafeTextMapPropagator: extract упал ({}) — возвращаю исходный context", ex.toString());
            // ВАЖНО: именно исходный context, а не Context.root() — иначе теряется W3C/B3 trace context.
            return context;
        }
    }

    @Override
    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
        try {
            delegate.inject(context, carrier, setter);
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            diagnostics.recordPropagatorFailure();
            log.warn("SafeTextMapPropagator: inject упал ({}) — заголовки не записаны", ex.toString());
            // Небезопасные/частичные заголовки не пишем; бизнес-вызов продолжается.
        }
    }
}
