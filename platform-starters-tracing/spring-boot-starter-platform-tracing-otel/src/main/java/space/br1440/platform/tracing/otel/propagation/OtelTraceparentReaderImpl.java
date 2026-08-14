package space.br1440.platform.tracing.otel.propagation;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class OtelTraceparentReaderImpl implements OtelTraceparentReader {

    public static final OtelTraceparentReaderImpl INSTANCE = new OtelTraceparentReaderImpl();

    private static final int MAX_LOGGED_CHARS = 128;
    private static final String TRUNCATED_SUFFIX = "…[truncated]";
    private static final Pattern NON_PRINTABLE_ASCII = Pattern.compile("[^\\x20-\\x7E]");
    private static final String HDR_TRACEPARENT = "traceparent";
    private static final String HDR_TRACESTATE = "tracestate";

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

    private static Map<String, String> buildCarrier(@Nonnull String traceparent, @Nullable String tracestate) {
        if (tracestate == null || tracestate.isBlank()) {
            return Map.of(HDR_TRACEPARENT, traceparent);
        }

        Map<String, String> carrier = new HashMap<>(2);
        carrier.put(HDR_TRACEPARENT, traceparent);
        carrier.put(HDR_TRACESTATE, tracestate);
        return carrier;
    }

    @Nullable
    private static String encodeTraceState(@Nonnull TraceState traceState) {
        if (traceState.isEmpty()) {
            return null;
        }

        StringBuilder wireFormat = new StringBuilder();
        traceState.forEach((key, value) -> {
            if (!wireFormat.isEmpty()) {
                wireFormat.append(',');
            }

            wireFormat.append(key).append('=').append(value);
        });

        return wireFormat.toString();
    }

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
