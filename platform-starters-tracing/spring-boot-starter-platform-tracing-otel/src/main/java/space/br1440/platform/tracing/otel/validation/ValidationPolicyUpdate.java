package space.br1440.platform.tracing.otel.validation;

import java.time.Instant;

import space.br1440.platform.common.util.StringUtils;

import lombok.experimental.UtilityClass;

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
                normalizeSource(source)
        );
    }

    public static String normalizeSource(String source) {
        if (StringUtils.isNullOrBlank(source)) {
            return "JMX";
        }

        return source.trim();
    }
}
