package space.br1440.platform.tracing.core.validation;

import space.br1440.platform.tracing.api.runtime.state.VersionedState;

import java.time.Instant;

/**
 * Иммутабельный снимок политики валидации span'ов (Фаза 14 / PR-8A): runtime-флаги
 * {@code enabled} и {@code strict}. Реализует {@link VersionedState} (монотонная версия, публикуется
 * через CAS).
 * <p>
 * Schema v1 fields: {@code enabled}, {@code strict}, {@code version}, {@code updatedAt},
 * {@code source}. Modes:
 * <ul>
 *   <li>{@code enabled=false} — validation bypass (passthrough on {@code onEnding})</li>
 *   <li>{@code enabled=true, strict=false} — lenient: annotate {@code platform.validation.missing},
 *       rate-limited WARN, span still exported</li>
 *   <li>{@code enabled=true, strict=true} — strict: {@code TracingValidationException} on missing
 *       required attrs</li>
 * </ul>
 * Validation degradation (annotation/warn/exception) is <b>not</b> dropped-span loss.
 */
public record ValidationSnapshot(
        boolean enabled,
        boolean strict,
        long version,
        Instant updatedAt,
        String source
) implements VersionedState {

    /**
     * Builds an immutable snapshot from policy flags (startup or test wiring).
     */
    public static ValidationSnapshot fromPolicy(
            boolean enabled,
            boolean strict,
            long version,
            Instant updatedAt,
            String source) {
        return new ValidationSnapshot(
                enabled, strict, version, updatedAt, ValidationPolicyUpdate.normalizeSource(source));
    }
}
