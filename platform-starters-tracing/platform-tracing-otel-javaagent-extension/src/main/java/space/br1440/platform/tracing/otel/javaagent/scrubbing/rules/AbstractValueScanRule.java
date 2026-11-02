package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import java.util.List;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.StringValueRule;

abstract class AbstractValueScanRule extends AbstractBuiltInRule implements StringValueRule {

    private final ScrubbingDecision matchDecision;

    AbstractValueScanRule(BuiltInSpanAttributeScrubbingRules descriptor, ScrubbingDecision matchDecision) {
        super(descriptor);
        this.matchDecision = matchDecision;
    }

    @Nonnull
    @Override
    public final ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
        if (value instanceof String text) {
            if (text.length() > ValueScanPolicy.MAX_INPUT_CHARS) {
                return ValueScanPolicy.INPUT_LIMIT_MASK;
            }

            return matches(text) ? matchDecision : ScrubbingDecision.keep();
        }

        if (!(value instanceof List<?> values)) {
            return ScrubbingDecision.keep();
        }

        if (values.size() > ValueScanPolicy.MAX_ARRAY_ELEMENTS) {
            return ValueScanPolicy.INPUT_LIMIT_MASK;
        }

        int remaining = ValueScanPolicy.MAX_INPUT_CHARS;
        for (Object element : values) {
            if (element == null) {
                continue;
            }

            if (!(element instanceof String text) || text.length() > remaining) {
                return ValueScanPolicy.INPUT_LIMIT_MASK;
            }

            remaining -= text.length();
        }

        for (Object element : values) {
            if (element instanceof String text && matches(text)) {
                return matchDecision;
            }
        }

        return ScrubbingDecision.keep();
    }

    protected abstract boolean matches(String text);
}
