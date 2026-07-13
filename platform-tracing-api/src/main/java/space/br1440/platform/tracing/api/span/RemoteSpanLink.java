package space.br1440.platform.tracing.api.span;

import jakarta.annotation.Nullable;

/**
 * Ссылка на внешний span.
 * <p>
 * Идентификаторы передаются в формате OpenTelemetry (hex, без префикса {@code 0x}).
 * {@code traceFlags} — байт W3C {@code traceflags} (sampled = {@code 0x01}).
 */
public record RemoteSpanLink(String traceId,
                             String spanId,
                             byte traceFlags,
                             @Nullable String traceState) {

    public static RemoteSpanLink sampled(String traceId, String spanId) {
        return new RemoteSpanLink(traceId, spanId, (byte) 1, null);
    }
}
