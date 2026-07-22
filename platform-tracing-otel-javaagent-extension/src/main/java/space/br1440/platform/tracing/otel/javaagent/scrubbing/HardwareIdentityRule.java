package space.br1440.platform.tracing.otel.javaagent.scrubbing;


import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило для идентификаторов оборудования: {@code hardwareId}, {@code imei}, {@code serialNumber}.
 * Key-based, HASH.
 */
final class HardwareIdentityRule extends AbstractBuiltInRule {

    private static final String[] TOKENS = {
            "hardwareid", "hwid", "serialnumber", "deviceserialnumber", "imei"
    };

    HardwareIdentityRule() {
        super(BuiltInSpanAttributeScrubbingRules.HARDWARE_IDENTITY);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
        if (KeyMatcher.containsAny(KeyMatcher.normalize(key), TOKENS)) {
            return ScrubbingDecision.hash("hardware-identity");
        }
        return ScrubbingDecision.keep();
    }
}
