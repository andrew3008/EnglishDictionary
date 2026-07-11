package space.br1440.platform.tracing.api.span;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Разбор W3C trace-контекста для предстартовых связей span'ов.
 * <p>
 * Soft {@link #parseTraceparent(String)} предназначен для циклов пакетного извлечения, где
 * отсутствующие или некорректные заголовки пропускаются. Strict {@link #requireTraceparent(String)}
 * используется построителем {@code fromRemoteContext(...)} и завершается с ошибкой при невалидном вводе.
 */
@UtilityClass
public final class RemoteContext {

    private static final String INVALID_ID = "0".repeat(32);
    private static final String INVALID_SPAN_ID = "0".repeat(16);

    /**
     * Разбирает значение W3C-заголовка {@code traceparent} в контекст связи, игнорируя невалидный ввод.
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
     * Разбирает значение W3C-заголовка {@code traceparent} или бросает исключение, если связь получить нельзя.
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
