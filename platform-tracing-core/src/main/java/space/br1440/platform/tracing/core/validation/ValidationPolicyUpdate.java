package space.br1440.platform.tracing.core.validation;

import lombok.experimental.UtilityClass;

import java.time.Instant;

/**
 * Формирует {@link ValidationSnapshot} для атомарного runtime-обновления политики.
 */
@UtilityClass
public final class ValidationPolicyUpdate {

    public static ValidationSnapshot buildNext(ValidationSnapshot previous,
                                               boolean enabled,
                                               boolean strict,
                                               String source) {
        return new ValidationSnapshot(
                enabled,
                strict,
                previous.version() + 1,
                Instant.now(),
                normalizeSource(source));
    }

    public static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "JMX";
        }

        return source.trim();
    }
}
