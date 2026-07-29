package space.br1440.platform.tracing.otel.javaagent.control.state;

import javax.management.openmbean.CompositeData;

/**
 * Test/benchmark white-box bridge to package-private {@link PrivateControlOpenTypeCodec}.
 *
 * <p>Lives in {@code src/test/java} in the same package so it can call
 * package-private methods without exports or reflection.
 *
 * <p>The codec instance is created once per bridge instance and is stateless
 * after construction — safe for repeated reuse inside a benchmark trial.
 */
public final class PrivateControlOpenTypeCodecAccess {

    private final PrivateControlOpenTypeCodec codec;

    public PrivateControlOpenTypeCodecAccess() {
        this.codec = new PrivateControlOpenTypeCodec();
    }

    /**
     * Encodes one logically consistent accepted-state snapshot for PRIV-R07.
     *
     * @param view snapshot obtained from {@code AcceptedPolicyAccess.current()};
     *             must be a single volatile read to guarantee consistency
     * @return {@code CompositeData} with type {@code PrivateControlWireTypes.ACCEPTED_COMPLETE}
     */
    public CompositeData encodeAcceptedComplete(AcceptedPolicyView view) {
        return codec.encodeAcceptedComplete(view);
    }

    /**
     * Decodes a Spring-side PRIV-R07 {@code CompositeData} payload.
     *
     * @param data wire-format request as produced by the Spring-side encoder
     * @return decoded snapshot
     * @throws PrivateControlDecodeException if the payload violates the wire contract
     */
    public DecodedPolicySnapshot decode(CompositeData data) {
        return codec.decode(data);
    }
}
