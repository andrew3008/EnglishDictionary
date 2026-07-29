package space.br1440.platform.tracing.otel.javaagent.control.state;

import javax.management.openmbean.CompositeData;

/**
 * Test/benchmark white-box bridge to package-private {@link PrivateControlOpenTypeCodec}.
 *
 * <p>Lives in {@code src/test/java} in the same package so it can call
 * package-private methods without exports or reflection. Consumed by
 * {@code platform-tracing-bench} P5.7 JMH via the {@code jmhWhiteBox} configuration.
 */
public final class PrivateControlOpenTypeCodecAccess {

    private final PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();

    /**
     * Encodes one logically consistent accepted-state snapshot for PRIV-R07.
     *
     * @param view snapshot from a single {@code AcceptedPolicyAccess.current()} read
     * @return {@code CompositeData} typed as {@code AcceptedCompleteSnapshot/v1}
     */
    public CompositeData encodeAcceptedComplete(AcceptedPolicyView view) {
        return codec.encodeAcceptedComplete(view);
    }
}
