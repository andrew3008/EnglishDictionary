package space.br1440.platform.tracing.api.propagation;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;

import java.util.Optional;

/**
 * Bridge-интерфейс для чтения W3C {@code traceparent} (и опционального {@code tracestate})
 * из HTTP-заголовков и преобразования в {@link RemoteSpanLink}.
 * <p>
 * Единственная каноническая реализация — {@code OtelTraceparentReaderImpl} в модуле
 * {@code platform-tracing-core}. Интерфейс живёт в {@code platform-tracing-api},
 * чтобы api-layer builders ({@code DefaultSpanSpecBuilder}) могли обращаться к нему
 * без compile-time зависимости на OpenTelemetry или core-модуль.
 * <p>
 * Прикладной код не должен реализовывать или инжектировать этот интерфейс напрямую.
 * Используйте builder-метод {@code fromTraceparent(...)}.
 */
public interface OtelTraceparentReader {

    /**
     * Читает W3C {@code traceparent} заголовок (без {@code tracestate}).
     * {@code RemoteSpanLink.traceState} будет {@code null}.
     *
     * @param traceparent сырое значение заголовка; допускается {@code null} или пустая строка
     * @return непустой {@link Optional}, если заголовок валиден; иначе — пустой
     */
    Optional<RemoteSpanLink> read(@Nullable String traceparent);

    /**
     * Читает W3C {@code traceparent} совместно со сопутствующим {@code tracestate} заголовком.
     * {@code RemoteSpanLink.traceState} заполняется, если {@code tracestate} присутствует и валиден.
     *
     * @param traceparent сырое значение {@code traceparent} заголовка
     * @param tracestate  сырое значение {@code tracestate} заголовка; допускается {@code null}
     * @return непустой {@link Optional}, если {@code traceparent} валиден; иначе — пустой
     */
    Optional<RemoteSpanLink> read(@Nullable String traceparent, @Nullable String tracestate);

    /**
     * Строгий вариант: бросает {@link IllegalArgumentException}, если заголовок невалиден.
     *
     * @param traceparent сырое значение заголовка; не должно быть {@code null}
     * @return валидный {@link RemoteSpanLink}
     * @throws NullPointerException     если {@code traceparent} равен {@code null}
     * @throws IllegalArgumentException если значение заголовка невалидно
     */
    RemoteSpanLink require(@Nonnull String traceparent);
}
