package space.br1440.platform.tracing.otel.propagation;

import java.util.Optional;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;

/**
 * Внутренний implementation-контракт чтения W3C {@code traceparent} (и опционального
 * {@code tracestate}) для manual tracing pipeline.
 *
 * <p>Каноническая реализация — {@link OtelTraceparentReaderImpl}. Reader инжектируется
 * в composition root ({@code DefaultSpanFactory}, {@code AbstractSemanticSpanBuilder})
 * и не является public application API.</p>
 *
 * <p>Прикладной код не должен реализовывать или инжектировать этот интерфейс напрямую.
 * Для связывания span используйте builder-метод {@code fromTraceparent(...)}.</p>
 */
public interface OtelTraceparentReader {

    /**
     * Читает W3C {@code traceparent} (без {@code tracestate}).
     * {@link RemoteSpanLink#traceState()} будет {@code null}.
     *
     * @param traceparent сырое значение заголовка; допускается {@code null} или blank
     * @return непустой {@link Optional}, если заголовок валиден; иначе пустой
     */
    Optional<RemoteSpanLink> read(@Nullable String traceparent);

    /**
     * Читает W3C {@code traceparent} совместно с {@code tracestate}.
     *
     * @param traceparent сырое значение {@code traceparent}; допускается {@code null} или blank
     * @param tracestate  сырое значение {@code tracestate}; допускается {@code null} или blank
     * @return непустой {@link Optional}, если {@code traceparent} валиден; иначе пустой
     */
    Optional<RemoteSpanLink> read(@Nullable String traceparent, @Nullable String tracestate);

    /**
     * Строгий вариант {@link #read(String)}: бросает исключение, если заголовок невалиден.
     *
     * @param traceparent сырое значение заголовка; не должно быть {@code null}
     * @return валидный {@link RemoteSpanLink}
     * @throws NullPointerException     если {@code traceparent} равен {@code null}
     * @throws IllegalArgumentException если значение заголовка невалидно
     */
    RemoteSpanLink require(@Nonnull String traceparent);
}
