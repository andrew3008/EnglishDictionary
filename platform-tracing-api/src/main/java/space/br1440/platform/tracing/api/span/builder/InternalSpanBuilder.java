package space.br1440.platform.tracing.api.span.builder;

import io.opentelemetry.api.common.AttributeKey;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanScope;

import java.util.function.Supplier;

/**
 * Типизированный builder внутреннего (INTERNAL) span'а бизнес-операции.
 * <p>
 * INTERNAL — единственная категория, которую НЕ создаёт OTel Java Agent, поэтому её builder
 * реализуется полноценно (нулевой риск double instrumentation). Builder направляет к корректной
 * семантике: явное низко-кардинальное имя, типизированные атрибуты, валидация контракта до старта.
 * <p>
 * Идиома — try-with-resources вокруг {@link #start()}:
 * <pre>{@code
 * try (SpanScope scope = tracing.internalSpan().name("checkout").start()) {
 *     // ...
 * }
 * }</pre>
 */
public interface InternalSpanBuilder {

    /** Явное низко-кардинальное имя span'а (НЕ raw URL/SQL/id). */
    @Nonnull
    InternalSpanBuilder name(@Nonnull String name);

    /** Типизированный атрибут (eager-значение). */
    @Nonnull
    <V> InternalSpanBuilder attribute(@Nonnull AttributeKey<V> key, @Nonnull V value);

    /**
     * Lazy-атрибут: дорогое значение вычисляется только если span будет писаться
     * ({@code isRecording()}-guard скрыт внутри builder'а). Запрещён для creation-time ключей
     * (sampling-relevant / используемых в имени) — для таких ключей бросается
     * {@link IllegalArgumentException}.
     * <p>
     * Метод назван отдельно (не перегрузка {@link #attribute}), чтобы избежать неоднозначности
     * вывода типов при передаче лямбды.
     */
    @Nonnull
    <V> InternalSpanBuilder lazyAttribute(@Nonnull AttributeKey<V> key, @Nonnull Supplier<? extends V> valueSupplier);

    /**
     * Явный override anti-double guard: создать новый span даже при re-entry платформы той же
     * категории. Для INTERNAL re-entry редок; метод присутствует для единообразия контракта.
     */
    @Nonnull
    InternalSpanBuilder forceNewSpan();

    /** Создаёт span (или, при re-entry платформы, обогащает текущий). Возвращает {@link SpanScope}. */
    @Nonnull
    SpanScope start();

}
