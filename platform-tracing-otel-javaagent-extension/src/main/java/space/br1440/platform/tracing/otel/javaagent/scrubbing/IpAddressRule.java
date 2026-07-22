package space.br1440.platform.tracing.otel.javaagent.scrubbing;


import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило для IP-адресов. Key-based, TRUNCATE (prefix-grouping /24 и /64).
 */
public final class IpAddressRule extends AbstractBuiltInRule {

    /** Маркер reason для IP-prefix усечения в процессоре. */
    public static final String REASON = "ip-address";

    private static final String[] TOKENS = {
            "ipaddress", "ipv4address", "ipv6address",
            "xforwardedfor", "remoteaddress", "clientaddress",
            "networkpeeraddress", "clientip"
    };

    IpAddressRule() {
        super(BuiltInSpanAttributeScrubbingRules.IP_ADDRESS);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
        if (KeyMatcher.containsAny(KeyMatcher.normalize(key), TOKENS)) {
            return ScrubbingDecision.truncate(REASON, -1);
        }
        return ScrubbingDecision.keep();
    }
}
