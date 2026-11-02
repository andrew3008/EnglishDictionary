package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

public interface TextTransformingRule extends SpanAttributeScrubbingRule {

    @Nullable
    String transform(@Nonnull String key, @Nonnull String value);

    @Nonnull
    @Override
    default ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
        if (!(value instanceof String text) || isExcluded(key)) {
            return ScrubbingDecision.keep();
        }

        String transformed = transform(key, text);
        if (transformed == null) {
            return ScrubbingDecision.drop(name());
        }

        return text.equals(transformed) ? ScrubbingDecision.keep() : ScrubbingDecision.mask(name());
    }
}
