package space.br1440.platform.tracing.otel.validation;

import space.br1440.platform.tracing.otel.runtime.versioned.VersionedState;

import java.time.Instant;

/**
 * Иммутабельный снимок политики валидации span'ов.
 * <p>
 * Поля schema v1: {@code enabled}, {@code strict}, {@code version}, {@code updatedAt},
 * {@code source}. Режимы:
 * <ul>
 *   <li>{@code enabled=false} — обход валидации в {@code onEnding}</li>
 *   <li>{@code enabled=true, strict=false} — мягкий режим:
 *       аннотация {@code platform.validation.missing},
 *       rate-limited WARN,
 *       span по-прежнему экспортируется</li>
 *   <li>{@code enabled=true, strict=true} — строгий режим:
 *       {@code TracingValidationException} при отсутствии обязательных атрибутов</li>
 * </ul>
 */
public record ValidationSnapshot(boolean enabled,
                                 boolean strict,
                                 long version,
                                 Instant updatedAt,
                                 String source
) implements VersionedState {

    public static ValidationSnapshot fromPolicy(boolean enabled,
                                                boolean strict,
                                                long version,
                                                Instant updatedAt,
                                                String source) {
        return new ValidationSnapshot(enabled, strict, version, updatedAt, ValidationPolicyUpdate.normalizeSource(source));
    }
}
