package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

import java.util.regex.Pattern;

/**
 * Правило обнаружения email-адресов в строковых атрибутах.
 */
final class EmailRule extends AbstractBuiltInRule {

    private static final Pattern PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
    );

    EmailRule() {
        super(BuiltInSpanAttributeScrubbingRules.EMAIL);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
        if (value instanceof String s && PATTERN.matcher(s).find()) {
            return ScrubbingDecision.hash("email");
        }

        return ScrubbingDecision.keep();
    }
}
