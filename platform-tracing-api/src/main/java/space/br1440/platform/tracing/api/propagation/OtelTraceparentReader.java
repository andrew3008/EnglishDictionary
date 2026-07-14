package space.br1440.platform.tracing.api.propagation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@UtilityClass
public final class OtelTraceparentReader {

    private static final int MAX_LOGGED_CHARS = 128;
    private static final Pattern NON_PRINTABLE_ASCII = Pattern.compile("[^\\x20-\\x7E]");
    private static final String TRACEPARENT = "traceparent";

    @Nonnull
    public static Optional<RemoteSpanLink> read(@Nullable String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Optional.empty();
        }

        Map<String, String> carrier = Map.of(TRACEPARENT, traceparent);
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
                null));
    }

    @Nonnull
    public static RemoteSpanLink require(@Nonnull String traceparent) {
        Objects.requireNonNull(traceparent, "traceparent");
        return read(traceparent)
                .orElseThrow(() -> new IllegalArgumentException("invalid traceparent: " + sanitize(traceparent)));
    }

    private static String sanitize(@Nonnull String raw) {
        String sanitized = NON_PRINTABLE_ASCII.matcher(raw.trim())
                .replaceAll("?");

        if (sanitized.length() <= MAX_LOGGED_CHARS) {
            return sanitized;
        }

        return sanitized.substring(0, MAX_LOGGED_CHARS);
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
