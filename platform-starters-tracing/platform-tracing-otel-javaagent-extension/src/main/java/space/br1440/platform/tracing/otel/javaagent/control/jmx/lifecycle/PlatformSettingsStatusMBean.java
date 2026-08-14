package space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle;

import java.util.Objects;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.ReflectionException;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdateSnapshot;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;

final class PlatformSettingsStatusMBean implements DynamicMBean {

    private static final MBeanInfo INFO = buildMBeanInfo();

    private final TracingSettingsStore store;
    private final LastSettingsUpdate diagnostics;

    PlatformSettingsStatusMBean(TracingSettingsStore store, LastSettingsUpdate diagnostics) {
        this.store = Objects.requireNonNull(store, "store");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public Object getAttribute(String attribute) throws AttributeNotFoundException {
        return attributeValue(attribute, store.current(), diagnostics.snapshot());
    }

    @Override
    public void setAttribute(Attribute attribute) throws MBeanException {
        throw new MBeanException(new UnsupportedOperationException("Control status is read-only"));
    }

    @Override
    public AttributeList getAttributes(String[] attributes) {
        TracingSettingsRevision accepted = store.current();
        LastSettingsUpdateSnapshot snapshot = diagnostics.snapshot();

        AttributeList result = new AttributeList();
        for (String attribute : attributes) {
            try {
                result.add(new Attribute(attribute, attributeValue(attribute, accepted, snapshot)));
            } catch (AttributeNotFoundException ignored) {
            }
        }

        return result;
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes) {
        return new AttributeList();
    }

    @Override
    public Object invoke(String actionName, Object[] params, String[] signature) throws ReflectionException {
        throw new ReflectionException(new NoSuchMethodException(actionName));
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return INFO;
    }

    private Object attributeValue(String attribute, TracingSettingsRevision accepted, LastSettingsUpdateSnapshot snapshot) throws AttributeNotFoundException {
        return switch (attribute) {
            case "RuntimeInstanceId" -> accepted.runtimeInstanceId();
            case "AcceptedSequence" -> accepted.acceptedSequence();
            case "AcceptedPolicyDigest" -> TracingSettingsDigest.sha256(accepted.policy());
            case "LastApplyOutcome" -> token(snapshot.latestOutcome());
            case "LastAttemptId" -> lastAttemptId(snapshot);
            case "LastFailureCode" -> failureCode(snapshot);
            default -> throw new AttributeNotFoundException("Unknown Control attribute: " + attribute);
        };
    }

    private static String lastAttemptId(LastSettingsUpdateSnapshot snapshot) {
        SettingsUpdateAttempt attempt = snapshot.latestAttempt();
        return (attempt == null) ? "" : attempt.attemptId();
    }

    private static String failureCode(LastSettingsUpdateSnapshot snapshot) {
        SettingsUpdateStatus outcome = snapshot.latestOutcome();
        return (outcome != null && !outcome.isSuccessful()) ? token(snapshot.latestMessageCode()) : "";
    }

    private static String token(Enum<?> value) {
        return (value == null) ? "" : value.name();
    }

    private static MBeanInfo buildMBeanInfo() {
        return new MBeanInfo(
                PlatformSettingsStatusMBean.class.getName(),
                "Accepted runtime settings diagnostics",
                buildAttributeMetaData(),
                new javax.management.MBeanConstructorInfo[0],
                new javax.management.MBeanOperationInfo[0],
                new javax.management.MBeanNotificationInfo[0]
        );
    }

    private static MBeanAttributeInfo[] buildAttributeMetaData() {
        return new MBeanAttributeInfo[]{
                attribute("RuntimeInstanceId", String.class, "Unique identifier for the current runtime instance"),
                attribute("AcceptedSequence", Long.TYPE, "The sequence number of the currently accepted policy"),
                attribute("AcceptedPolicyDigest", String.class, "SHA-256 digest of the currently accepted policy"),
                attribute("LastApplyOutcome", String.class, "Status outcome of the most recent apply attempt"),
                attribute("LastAttemptId", String.class, "The identifier of the last update attempt"),
                attribute("LastFailureCode", String.class, "Failure code of the last attempt, if applicable")
        };
    }

    private static MBeanAttributeInfo attribute(String name, Class<?> type, String description) {
        return new MBeanAttributeInfo(
                name,
                type.getName(),
                description,
                true,
                false,
                false
        );
    }
}
