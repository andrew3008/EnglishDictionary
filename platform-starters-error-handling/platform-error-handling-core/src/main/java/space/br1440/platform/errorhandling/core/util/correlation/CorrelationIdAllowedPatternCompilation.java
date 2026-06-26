package space.br1440.platform.errorhandling.core.util.correlation;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.util.regex.Pattern;

@UtilityClass
public final class CorrelationIdAllowedPatternCompilation {

    private static final Pattern DEFAULT_ALLOWED_PATTERN_COMPILED =
            Pattern.compile(ErrorHandlingProperties.CorrelationId.DEFAULT_ALLOWED_PATTERN);

    @NonNull
    public static Pattern compile(@NonNull ErrorHandlingProperties.CorrelationId correlationIdConfig) {
        String configured = correlationIdConfig.getAllowedPattern();
        return ErrorHandlingProperties.CorrelationId.DEFAULT_ALLOWED_PATTERN.equals(configured)
                ? DEFAULT_ALLOWED_PATTERN_COMPILED
                : Pattern.compile(configured);
    }
}
