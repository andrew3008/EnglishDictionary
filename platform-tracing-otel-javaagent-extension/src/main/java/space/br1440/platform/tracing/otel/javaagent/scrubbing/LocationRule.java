package space.br1440.platform.tracing.otel.javaagent.scrubbing;


import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило для геолокации: {@code latitude}, {@code longitude}, {@code altitude}, {@code elevation}.
 * Key-based, DROP. Для {@code DOUBLE} процессор применяет type-neutral sentinel.
 */
final class LocationRule extends AbstractBuiltInRule {

    private static final String[] TOKENS = {"latitude", "longitude", "altitude", "elevation"};

    LocationRule() {
        super(BuiltInSpanAttributeScrubbingRules.LOCATION);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
        if (KeyMatcher.containsAny(KeyMatcher.normalize(key), TOKENS)) {
            return ScrubbingDecision.drop("location");
        }
        return ScrubbingDecision.keep();
    }
}
