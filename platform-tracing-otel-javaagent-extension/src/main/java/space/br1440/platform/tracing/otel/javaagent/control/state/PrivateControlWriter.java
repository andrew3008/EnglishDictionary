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
 * Единственная mutating operation private control plane.
 */
final class PrivateControlWriter implements DynamicMBean {

    private static final MBeanInfo INFO = new MBeanInfo(
            PrivateControlWriter.class.getName(),
            "Strict complete-snapshot private writer",
            null,
            null,
            new MBeanOperationInfo[]{
                    new MBeanOperationInfo(
                            "applySnapshot",
                            "Applies one complete Category A snapshot",
                            new MBeanParameterInfo[]{
                                    new MBeanParameterInfo(
                                            "request",
                                            CompositeData.class.getName(),
                                            "ApplySnapshotRequest.v1")
                            },
                            CompositeData.class.getName(),
                            MBeanOperationInfo.ACTION)
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
        if (!"applySnapshot".equals(actionName)
                || params == null
                || params.length != 1
                || !(params[0] instanceof CompositeData request)) {
            throw new ReflectionException(new NoSuchMethodException(actionName));
        }
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

    @Override
    public MBeanInfo getMBeanInfo() {
        return INFO;
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
