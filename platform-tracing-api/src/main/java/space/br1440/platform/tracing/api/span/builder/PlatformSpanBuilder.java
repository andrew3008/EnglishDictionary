package space.br1440.platform.tracing.api.span.builder;

import io.opentelemetry.api.common.AttributeKey;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanScope;

import java.util.function.Supplier;

/**
 * Общий fluent-контракт типизированных platform-builder'ов (self-typed, чтобы category-методы
 * подклассов сохраняли конкретный тип в цепочке).
 */
public interface PlatformSpanBuilder<B extends PlatformSpanBuilder<B>> {

    /** Явное низко-кардинальное имя (для большинства категорий имя строится из атрибутов). */
    @Nonnull
    B name(@Nonnull String name);

    /** Типизированный атрибут (eager-значение). */
    @Nonnull
    <V> B attribute(@Nonnull AttributeKey<V> key, @Nonnull V value);

    /**
     * Lazy-атрибут: дорогое значение вычисляется только если span пишется ({@code isRecording()}).
     * Запрещён для creation-time ключей (sampling-relevant / используемых в имени).
     */
    @Nonnull
    <V> B lazyAttribute(@Nonnull AttributeKey<V> key, @Nonnull Supplier<? extends V> valueSupplier);

    @Nonnull
    B unsafeAttribute(@Nonnull String key, @Nonnull String value);

    /** Override anti-double guard: всегда создавать новый span (даже при re-entry платформы). */
    @Nonnull
    B forceNewSpan();

    /** Создаёт span (или, при re-entry платформы, обогащает текущий). */
    @Nonnull
    SpanScope start();

}
