package space.br1440.platform.tracing.core.propagation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;
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
 * OTel-backed implementation of {@link OtelTraceparentReader}.
 * <p>
 * Lives in {@code platform-tracing-core} so that OpenTelemetry API imports are confined
 * to the core module and do not leak into {@code platform-tracing-api}.
 * <p>
 * A static singleton {@link #INSTANCE} is exposed for use by api-layer builders via the
 * {@link OtelTraceparentReader} interface. Direct use outside of
 * {@code DefaultSpanSpecBuilder} and {@code AbstractSemanticSpanBuilder} is not permitted
 * (enforced by ArchUnit {@code OTEL_TRACEPARENT_READER_ACCESS_RESTRICTED}).
 */
@Slf4j
public final class OtelTraceparentReaderImpl implements OtelTraceparentReader {

    /** Singleton instance provided to api-layer builders at startup. */
    public static final OtelTraceparentReaderImpl INSTANCE = new OtelTraceparentReaderImpl();

    private static final int MAX_LOGGED_CHARS = 128;
    private static final String TRUNCATED_SUFFIX = "\u2026[truncated]";
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

        String encodedTraceState = encodeTraceState(spanContext.getTraceState());
        return Optional.of(new RemoteSpanLink(
                spanContext.getTraceId(),
                spanContext.getSpanId(),
                spanContext.getTraceFlags().asByte(),
                encodedTraceState));
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
    // Internal helpers
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

    @Nullable
    private static String encodeTraceState(@Nonnull TraceState traceState) {
        if (traceState.isEmpty()) {
            return null;
        }
        // OTel TraceState.toString() produces the W3C wire-format: k1=v1,k2=v2
        return traceState.toString();
    }

    @Nonnull
    static String sanitize(@Nonnull String raw) {
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
