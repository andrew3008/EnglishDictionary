package space.br1440.platform.tracing.api.propagation;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;

import java.util.Optional;

/**
 * Bridge-интерфейс для чтения W3C {@code traceparent} (и опционального {@code tracestate})
 * из HTTP-заголовков и преобразования в {@link RemoteSpanLink}.
 * <p>
 * Каноническая реализация находится в implementation-модуле и передаётся manual builder-ам
 * через application composition root без глобального поиска provider-а.
 * <p>
 * Прикладной код не должен реализовывать или инжектировать этот интерфейс напрямую.
 * Используйте manual builder, полученный из {@code SpanFactory}.
 */
public interface OtelTraceparentReader {

    /**
     * Читает W3C {@code traceparent} заголовок (без {@code tracestate}).
     * {@code RemoteSpanLink.traceState} будет {@code null}.
     *
     * @param traceparent сырое значение заголовка; допускается {@code null} или пустая строка
     * @return непустой {@link Optional}, если заголовок валиден; пустой — иначе
     */
    Optional<RemoteSpanLink> read(@Nullable String traceparent);

    /**
     * Читает W3C {@code traceparent} совместно со сопутствующим {@code tracestate} заголовком.
     * {@code RemoteSpanLink.traceState} заполняется, если {@code tracestate} присутствует и валиден.
     *
     * @param traceparent сырое значение {@code traceparent} заголовка
     * @param tracestate  сырое значение {@code tracestate} заголовка; допускается {@code null}
     * @return непустой {@link Optional}, если {@code traceparent} валиден; пустой — иначе
     */
    Optional<RemoteSpanLink> read(@Nullable String traceparent, @Nullable String tracestate);

    /**
     * Строгий вариант метода {@link #read(String)}: бросает исключение, если заголовок невалиден.
     *
     * @param traceparent сырое значение заголовка; не должно быть {@code null}
     * @return валидный {@link RemoteSpanLink}
     * @throws NullPointerException     если {@code traceparent} равен {@code null}
     * @throws IllegalArgumentException если значение заголовка невалидно
     */
    RemoteSpanLink require(@Nonnull String traceparent);

}
