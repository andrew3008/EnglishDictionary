package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.runtime.scrubbing.SensitiveValueDetector;

/**
 * Правило обнаружения email-адресов в строковых атрибутах.
 */
final class EmailRule extends AbstractValueScanRule {

    EmailRule() {
        super(BuiltInSpanAttributeScrubbingRules.EMAIL, ScrubbingDecision.hash("email"));
    }

    @Override
    protected boolean matches(String text) {
        return SensitiveValueDetector.containsEmail(text);
    }
}
