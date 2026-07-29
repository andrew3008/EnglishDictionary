package space.br1440.platform.tracing.otel.javaagent.control.state;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;

/**
 * Private control-plane DynamicMBean registered on the private MBeanServer only.
 *
 * <p>Exposes two operations:
 * <ol>
 *   <li>{@code applySnapshot} – the sole private mutation. Accepts a complete
 *       Category&nbsp;A snapshot encoded as {@link CompositeData} and applies
 *       it via a single CAS. Impact: {@code ACTION}.</li>
 *   <li>{@code readAcceptedComplete} – PRIV-R07 read-only operation.
 *       Returns one immutable {@link CompositeData} snapshot of the currently
 *       accepted policy. No mutation, no sequence change, no second state owner.
 *       Impact: {@code INFO}. Status: APPROVED (Architecture Committee, 2026-07-29).</li>
 * </ol>
 *
 * <p>This MBean is absent from the platform {@code MBeanServer} and is never
 * reachable over a remote JMX connector.
 */
final class PrivateControlWriter implements DynamicMBean {

    private static final String OP_APPLY    = "applySnapshot";
    private static final String OP_READ     = "readAcceptedComplete";

    private static final MBeanInfo INFO = new MBeanInfo(
            PrivateControlWriter.class.getName(),
            "Strict complete-snapshot private writer and accepted-policy reader",
            null,
            null,
            new MBeanOperationInfo[]{
                    new MBeanOperationInfo(
                            OP_APPLY,
                            "Applies one complete Category A snapshot",
                            new MBeanParameterInfo[]{
                                    new MBeanParameterInfo(
                                            "request",
                                            CompositeData.class.getName(),
                                            "ApplySnapshotRequest.v1")
                            },
                            CompositeData.class.getName(),
                            MBeanOperationInfo.ACTION),
                    new MBeanOperationInfo(
                            OP_READ,
                            "Returns immutable accepted-policy snapshot. PRIV-R07. APPROVED.",
                            new MBeanParameterInfo[0],
                            CompositeData.class.getName(),
                            MBeanOperationInfo.INFO)
            },
            null);

    private final AcceptedPolicyCoordinator coordinator;
    private final AcceptedPolicyStateStore store;
    private final PrivateControlOpenTypeCodec codec;

    PrivateControlWriter(
            AcceptedPolicyCoordinator coordinator,
            AcceptedPolicyStateStore store,
            PrivateControlOpenTypeCodec codec) {
        this.coordinator = coordinator;
        this.store = store;
        this.codec = codec;
    }

    @Override
    public Object getAttribute(String attribute) {
        throw new IllegalArgumentException("Private writer has no attributes");
    }

    @Override
    public void setAttribute(Attribute attribute) {
        throw new UnsupportedOperationException("Private writer has no writable attributes");
    }

    @Override
    public AttributeList getAttributes(String[] attributes) {
        return new AttributeList();
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes) {
        return new AttributeList();
    }

    @Override
    public Object invoke(String actionName, Object[] params, String[] signature)
            throws MBeanException, ReflectionException {
        if (OP_READ.equals(actionName)
                && isEmpty(params)
                && isEmpty(signature)) {
            // Single volatile envelope load — consistent policy + sequence + runtimeInstanceId
            AcceptedPolicyView snapshot = store.current();
            return codec.encodeAcceptedComplete(snapshot);
        }
        if (OP_APPLY.equals(actionName)
                && params != null
                && params.length == 1
                && params[0] instanceof CompositeData request
                && signature != null
                && signature.length == 1
                && CompositeData.class.getName().equals(signature[0])) {
            String attemptId = extractAttemptId(request);
            try {
                DecodedPolicySnapshot decoded = codec.decode(request);
                PolicyApplyResult result = coordinator.apply(
                        decoded.attempt(),
                        decoded.expectedBase(),
                        decoded.policy());
                return codec.encode(result, decoded.attempt().attemptId());
            } catch (PrivateControlDecodeException exception) {
                AcceptedPolicyEnvelope accepted = store.current();
                PolicyApplyResult rejected = new PolicyApplyResult(
                        PolicyApplyOutcome.REJECTED,
                        accepted,
                        EffectiveTracingPolicyDigest.sha256(accepted.policy()),
                        exception.messageCode());
                return codec.encode(rejected, attemptId);
            } catch (RuntimeException exception) {
                AcceptedPolicyEnvelope accepted = store.current();
                PolicyApplyResult failed = new PolicyApplyResult(
                        PolicyApplyOutcome.FAILED,
                        accepted,
                        EffectiveTracingPolicyDigest.sha256(accepted.policy()),
                        PolicyMessageCode.INTERNAL_FAILURE);
                return codec.encode(failed, attemptId);
            }
        }
        throw new ReflectionException(new NoSuchMethodException(actionName));
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return INFO;
    }

    /**
     * Canonical empty-array contract: per the {@link javax.management.MBeanServer#invoke}
     * Javadoc, {@code null} and a zero-length array are equivalent ways to express
     * "no arguments" — both are accepted here for {@code readAcceptedComplete}'s
     * {@code params} and {@code signature}. Any non-empty array (wrong argument count,
     * or a non-empty signature accompanying empty params) is rejected.
     */
    private static boolean isEmpty(Object[] array) {
        return array == null || array.length == 0;
    }

    private static boolean isEmpty(String[] array) {
        return array == null || array.length == 0;
    }

    private static String extractAttemptId(CompositeData request) {
        try {
            Object value = request.get("attemptId");
            return value instanceof String text && !text.isBlank() ? text : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
