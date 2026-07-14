package space.br1440.platform.tracing.core.propagation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.api.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * OTel-backed реализация интерфейса {@link OtelTraceparentReader}.
 * <p>
 * Располагается в {@code platform-tracing-core}, чтобы импорты OpenTelemetry API
 * были ограничены core-модулем и не проникали в {@code platform-tracing-api}.
 * <p>
 * Статический singleton {@link #INSTANCE} предоставляется api-layer builders'ам
 * через интерфейс {@link OtelTraceparentReader}. Прямое использование вне
 * {@code DefaultSpanSpecBuilder} и {@code AbstractSemanticSpanBuilder} запрещено
 * (enforced ArchUnit-правилом {@code OTEL_TRACEPARENT_READER_ACCESS_RESTRICTED}).
 */
@Slf4j
public final class OtelTraceparentReaderImpl implements OtelTraceparentReader {

    /** Singleton instance, передаваемый api-layer builders'ам при старте. */
    public static final OtelTraceparentReaderImpl INSTANCE = new OtelTraceparentReaderImpl();

    private static final int MAX_LOGGED_CHARS = 128;
    private static final String TRUNCATED_SUFFIX = "\u2026[truncated]";
    private static final Pattern NON_PRINTABLE_ASCII = Pattern.compile("[^\\x20-\\x7E]");
    private static final String HDR_TRACEPARENT = "traceparent";
    private static final String HDR_TRACESTATE = "tracestate";

    /** Приватный конструктор — используйте {@link #INSTANCE}. */
    private OtelTraceparentReaderImpl() {
    }

    @Override
    @Nonnull
    public Optional<RemoteSpanLink> read(@Nullable String traceparent) {
        return read(traceparent, null);
    }

    @Override
    @Nonnull
    public Optional<RemoteSpanLink> read(@Nullable String traceparent, @Nullable String tracestate) {
        if (traceparent == null || traceparent.isBlank()) {
            return Optional.empty();
        }

        Map<String, String> carrier = buildCarrier(traceparent, tracestate);
        Context context = W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), carrier, CarrierGetter.INSTANCE);

        SpanContext spanContext = Span.fromContext(context).getSpanContext();
        if (!spanContext.isValid()) {
            log.debug("rejected traceparent: {}", sanitize(traceparent));
            return Optional.empty();
        }

        return Optional.of(new RemoteSpanLink(
                spanContext.getTraceId(),
                spanContext.getSpanId(),
                spanContext.getTraceFlags().asByte(),
                encodeTraceState(spanContext.getTraceState())));
    }

    @Override
    @Nonnull
    public RemoteSpanLink require(@Nonnull String traceparent) {
        Objects.requireNonNull(traceparent, "traceparent");

        String sanitized = sanitize(traceparent);
        return read(traceparent)
                .orElseThrow(() -> new IllegalArgumentException("invalid traceparent: " + sanitized));
    }

    // -------------------------------------------------------------------------
    // Внутренние вспомогательные методы
    // -------------------------------------------------------------------------

    private static Map<String, String> buildCarrier(@Nonnull String traceparent, @Nullable String tracestate) {
        if (tracestate == null || tracestate.isBlank()) {
            return Map.of(HDR_TRACEPARENT, traceparent);
        }

        Map<String, String> carrier = new HashMap<>(2);
        carrier.put(HDR_TRACEPARENT, traceparent);
        carrier.put(HDR_TRACESTATE, tracestate);
        return carrier;
    }

    /**
     * Кодирует {@link TraceState} в W3C wire-format строку ({@code k1=v1,k2=v2}),
     * либо возвращает {@code null}, если state пустой.
     * <p>
     * Делегирует вызов {@link TraceState#toString()} — это каноническая OTel-реализация
     * сериализации W3C tracestate. Ручной {@code StringBuilder} не нужен:
     * корректность покрывается test suite самого OTel.
     */
    @Nullable
    private static String encodeTraceState(@Nonnull TraceState traceState) {
        if (traceState.isEmpty()) {
            return null;
        }
        // TraceState.toString() возвращает W3C wire-format: k1=v1,k2=v2
        return traceState.toString();
    }

    /**
     * Санирует raw-значение header для безопасного логирования:
     * <ul>
     *   <li>Заменяет non-printable ASCII символы на {@code ?}.</li>
     *   <li>Обрезает строку до {@value #MAX_LOGGED_CHARS} символов и добавляет
     *       суффикс {@value #TRUNCATED_SUFFIX}, если входная строка длиннее.</li>
     * </ul>
     * <p>
     * Видимость {@code public} (а не {@code private}) нужна для того, чтобы
     * {@code OtelTraceparentReaderTest} в test sources модуля {@code platform-tracing-api}
     * мог проверить поведение truncation напрямую, без reflection.
     * Метод не должен использоваться вне logging-контекстов.
     */
    @Nonnull
    public static String sanitize(@Nonnull String raw) {
        String sanitized = NON_PRINTABLE_ASCII.matcher(raw.trim()).replaceAll("?");
        if (sanitized.length() <= MAX_LOGGED_CHARS) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_LOGGED_CHARS) + TRUNCATED_SUFFIX;
    }

    private enum CarrierGetter implements TextMapGetter<Map<String, String>> {
        INSTANCE;

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        @Nullable
        public String get(@Nullable Map<String, String> carrier, @Nonnull String key) {
            if (carrier == null) {
                return null;
            }
            return carrier.get(key.toLowerCase(Locale.ROOT));
        }
    }
}
