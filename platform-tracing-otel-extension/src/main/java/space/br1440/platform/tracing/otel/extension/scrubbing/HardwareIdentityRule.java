package space.br1440.platform.tracing.otel.extension.scrubbing;


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
        super(BuiltInSensitiveDataRules.HARDWARE_IDENTITY);
    }

    @Override
    public ScrubbingDecision evaluate(String key, Object value) {
        if (KeyMatcher.containsAny(KeyMatcher.normalize(key), TOKENS)) {
            return ScrubbingDecision.hash("hardware-identity");
        }
        return ScrubbingDecision.keep();
    }
}
