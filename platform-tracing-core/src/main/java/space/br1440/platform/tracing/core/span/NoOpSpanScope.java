package space.br1440.platform.tracing.core.span;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.SpanScope;

/**
 * No-op реализация {@link SpanScope} для runtime kill-switch'а фасада (Фаза 14).
 * <p>
 * Когда платформенный фасад выключен в рантайме
 * ({@code DefaultPlatformTracing.setFacadeEnabled(false)}), методы создания span'а возвращают
 * этот синглтон вместо {@link OwningSpanScope}: никакой span не создаётся и не активируется в
 * контексте, все методы — безопасные no-op'ы, {@link #close()} ничего не закрывает. Поведение
 * try-with-resources на стороне приложения остаётся корректным.
 * <p>
 * Это политика (kill-switch), а не топология: процессорная цепочка и exporter не меняются.
 *
 * <p>Без состояния и потокобезопасен — используется единый {@link #INSTANCE}.
 */
public final class NoOpSpanScope implements SpanScope {

    public static final NoOpSpanScope INSTANCE = new NoOpSpanScope();

    private NoOpSpanScope() {
    }

    @Override
    @Nonnull
    public SpanScope setAttribute(@Nonnull String key, @Nullable String value) {
        return this;
    }

    @Override
    @Nonnull
    public SpanScope setAttribute(@Nonnull String key, long value) {
        return this;
    }

    @Override
    @Nonnull
    public SpanScope setAttribute(@Nonnull String key, double value) {
        return this;
    }

    @Override
    @Nonnull
    public SpanScope setAttribute(@Nonnull String key, boolean value) {
        return this;
    }

    @Override
    @Nonnull
    public SpanScope addEvent(@Nonnull String name) {
        return this;
    }

    @Override
    @Nonnull
    public SpanScope setResult(@Nonnull SpanResult result) {
        return this;
    }

    @Override
    @Nonnull
    public SpanScope recordException(@Nullable Throwable throwable) {
        return this;
    }

    @Override
    public void close() {
        // no-op
    }
}
