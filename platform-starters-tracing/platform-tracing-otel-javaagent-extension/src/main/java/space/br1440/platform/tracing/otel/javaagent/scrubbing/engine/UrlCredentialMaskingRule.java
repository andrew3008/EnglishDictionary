package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.runtime.scrubbing.UrlCredentialMasker;

public final class UrlCredentialMaskingRule implements SpanAttributeScrubbingRule {

    public static final String NAME = "url-credentials";
    public static final int PRIORITY = 5;

    @Nonnull
    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean critical() {
        return true;
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

        if (!(value instanceof String text)) {
            return ScrubbingDecision.drop(name());
        }

        String masked = mask(text);
        if (masked == null) {
            return ScrubbingDecision.drop(name());
        }

        return text.equals(masked) ? ScrubbingDecision.keep() : ScrubbingDecision.mask(name());
    }

    @Nullable
    String mask(@Nonnull String value) {
        return UrlCredentialMasker.mask(value);
    }
}
