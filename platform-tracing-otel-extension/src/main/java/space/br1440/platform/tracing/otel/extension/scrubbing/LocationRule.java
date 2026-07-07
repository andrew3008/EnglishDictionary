package space.br1440.platform.tracing.otel.extension.scrubbing;


import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило для геолокации: {@code latitude}, {@code longitude}, {@code altitude}, {@code elevation}.
 * Key-based, DROP. Для {@code DOUBLE} процессор применяет type-neutral sentinel.
 */
final class LocationRule extends AbstractBuiltInRule {

    private static final String[] TOKENS = {"latitude", "longitude", "altitude", "elevation"};

    LocationRule() {
        super(BuiltInSensitiveDataRules.LOCATION);
    }

    @Override
    public ScrubbingDecision evaluate(String key, Object value) {
        if (KeyMatcher.containsAny(KeyMatcher.normalize(key), TOKENS)) {
            return ScrubbingDecision.drop("location");
        }
        return ScrubbingDecision.keep();
    }
}
