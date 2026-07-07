package space.br1440.platform.tracing.api.span;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * W3C trace context parsing for pre-start span links.
 * <p>
 * Lenient {@link #parseTraceparent(String)} is intended for batch extraction loops where
 * missing or malformed headers are skipped. Strict {@link #requireTraceparent(String)} is
 * used by builder {@code fromRemoteContext(...)} and fails fast on invalid input.
 */
public final class RemoteContext {

    private static final String INVALID_ID = "0".repeat(32);
    private static final String INVALID_SPAN_ID = "0".repeat(16);

    private RemoteContext() {
    }

    /**
     * Parses a W3C {@code traceparent} header value into a link context, ignoring invalid input.
     */
    @Nonnull
    public static Optional<SpanLinkContext> parseTraceparent(@Nullable String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Optional.empty();
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length < 4) {
            return Optional.empty();
        }
        String traceId = normalizeHex(parts[1], 32);
        String spanId = normalizeHex(parts[2], 16);
        if (traceId == null || spanId == null) {
            return Optional.empty();
        }
        if (INVALID_ID.equals(traceId) || INVALID_SPAN_ID.equals(spanId)) {
            return Optional.empty();
        }
        byte flags;
        try {
            flags = (byte) Integer.parseInt(parts[3], 16);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        return Optional.of(new SpanLinkContext(traceId, spanId, flags, null));
    }

    /**
     * Parses a W3C {@code traceparent} header value or throws if it cannot produce a valid link.
     */
    @Nonnull
    public static SpanLinkContext requireTraceparent(@Nonnull String traceparent) {
        Objects.requireNonNull(traceparent, "traceparent");
        return parseTraceparent(traceparent)
                .orElseThrow(() -> new IllegalArgumentException("invalid traceparent: " + traceparent));
    }

    @Nullable
    private static String normalizeHex(@Nonnull String raw, int expectedLength) {
        if (raw.length() != expectedLength) {
            return null;
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return null;
            }
        }
        return normalized;
    }
}
