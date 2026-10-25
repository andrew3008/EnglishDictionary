package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import java.util.regex.Pattern;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило обнаружения email-адресов в строковых атрибутах.
 */
final class EmailRule extends AbstractBuiltInRule {

    static final int MAX_SCAN_LENGTH = 4_096;

    private static final Pattern PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++"
                    + "@(?:[A-Za-z0-9-]{1,63}+\\.)++[A-Za-z]{2,63}+(?![A-Za-z0-9-])"
    );

    EmailRule() {
        super(BuiltInSpanAttributeScrubbingRules.EMAIL);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
        if (!(value instanceof String text)) {
            return ScrubbingDecision.keep();
        }
        if (text.length() > MAX_SCAN_LENGTH) {
            return ScrubbingDecision.hash("email");
        }
        if (PATTERN.matcher(text).find()) {
            return ScrubbingDecision.hash("email");
        }

        return ScrubbingDecision.keep();
    }
}
