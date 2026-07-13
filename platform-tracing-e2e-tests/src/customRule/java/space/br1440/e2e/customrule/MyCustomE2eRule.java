package space.br1440.e2e.customrule;


import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

public class MyCustomE2eRule implements SpanAttributeScrubbingRule {

    @Nonnull
    @Override
    public String name() {
        return "custom-e2e-rule";
    }

    @Override
    public int priority() {
        return 999;
    }

    @Override
    public boolean isExcluded(@Nonnull String key) {
        return !"e2e.custom.marker".equals(key);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
        if ("e2e.custom.marker".equals(key)) {
            return ScrubbingDecision.mask(name());
        }
        return ScrubbingDecision.keep();
    }
}
