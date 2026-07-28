package space.br1440.platform.tracing.otel.javaagent.control.state;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.ReflectionException;
import java.util.function.BooleanSupplier;

/**
 * E7-approved read-only Control projection, подготавливаемая для P6 cutover.
 */
final class PlatformControlReadProjection implements DynamicMBean {

    private static final MBeanInfo INFO = new MBeanInfo(
            PlatformControlReadProjection.class.getName(),
            "Accepted runtime policy diagnostics",
            new MBeanAttributeInfo[]{
                    attribute("RuntimeInstanceId", String.class),
                    attribute("AcceptedSequence", Long.TYPE),
                    attribute("AcceptedPolicyDigest", String.class),
                    attribute("LastApplyOutcome", String.class),
                    attribute("LastAttemptId", String.class),
                    attribute("LastFailureCode", String.class),
                    attribute("StructuralDrift", Boolean.TYPE),
                    attribute("RestartRequired", Boolean.TYPE)
            },
            null,
            null,
            null);

    private final AcceptedPolicyStateStore store;
    private final PolicyApplyDiagnostics diagnostics;
    private final BooleanSupplier structuralDrift;
    private final BooleanSupplier restartRequired;

    PlatformControlReadProjection(
            AcceptedPolicyStateStore store,
            PolicyApplyDiagnostics diagnostics,
            BooleanSupplier structuralDrift,
            BooleanSupplier restartRequired) {
        this.store = store;
        this.diagnostics = diagnostics;
        this.structuralDrift = structuralDrift;
        this.restartRequired = restartRequired;
    }

    @Override
    public Object getAttribute(String attribute) {
        AcceptedPolicyEnvelope accepted = store.current();
        PolicyApplyDiagnosticsSnapshot snapshot = diagnostics.snapshot();
        return switch (attribute) {
            case "RuntimeInstanceId" -> accepted.runtimeInstanceId();
            case "AcceptedSequence" -> accepted.acceptedSequence();
            case "AcceptedPolicyDigest" -> EffectiveTracingPolicyDigest.sha256(accepted.policy());
            case "LastApplyOutcome" -> token(snapshot.latestOutcome());
            case "LastAttemptId" -> snapshot.latestAttempt() == null
                    ? ""
                    : snapshot.latestAttempt().attemptId();
            case "LastFailureCode" -> failureCode(snapshot);
            case "StructuralDrift" -> structuralDrift.getAsBoolean();
            case "RestartRequired" -> restartRequired.getAsBoolean();
            default -> throw new IllegalArgumentException("Unknown Control attribute: " + attribute);
        };
    }

    @Override
    public void setAttribute(Attribute attribute) {
        throw new UnsupportedOperationException("Control projection is read-only");
    }

    @Override
    public AttributeList getAttributes(String[] attributes) {
        AttributeList result = new AttributeList();
        for (String attribute : attributes) {
            result.add(new Attribute(attribute, getAttribute(attribute)));
        }
        return result;
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes) {
        return new AttributeList();
    }

    @Override
    public Object invoke(String actionName, Object[] params, String[] signature)
            throws ReflectionException {
        throw new ReflectionException(new NoSuchMethodException(actionName));
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return INFO;
    }

    private static String failureCode(PolicyApplyDiagnosticsSnapshot snapshot) {
        PolicyApplyOutcome outcome = snapshot.latestOutcome();
        if (outcome == PolicyApplyOutcome.REJECTED
                || outcome == PolicyApplyOutcome.STALE
                || outcome == PolicyApplyOutcome.FAILED) {
            return token(snapshot.latestMessageCode());
        }
        return "";
    }

    private static String token(Enum<?> value) {
        return value == null ? "" : value.name();
    }

    private static MBeanAttributeInfo attribute(String name, Class<?> type) {
        return new MBeanAttributeInfo(name, type.getName(), name, true, false, false);
    }
}
