package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.TextTransformingRule;
import space.br1440.platform.tracing.runtime.scrubbing.UrlCredentialMasker;

final class UrlCredentialMaskingRule extends AbstractBuiltInRule implements TextTransformingRule {

    UrlCredentialMaskingRule() {
        super(BuiltInSpanAttributeScrubbingRules.URL_CREDENTIALS);
    }

    @Override
    public boolean isExcluded(@Nonnull String key) {
        return !"exception.message".equals(key) && !"status.description".equals(key);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
        if (isExcluded(key)) {
            return ScrubbingDecision.keep();
        }

        if (!(value instanceof String)) {
            return ScrubbingDecision.drop(name());
        }

        return TextTransformingRule.super.evaluate(key, value);
    }

    @Nullable
    @Override
    public String transform(@Nonnull String key, @Nonnull String value) {
        return UrlCredentialMasker.mask(value);
    }
}
