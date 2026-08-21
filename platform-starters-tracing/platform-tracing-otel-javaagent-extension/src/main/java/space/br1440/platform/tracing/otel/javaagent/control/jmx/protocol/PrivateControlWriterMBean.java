package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import java.util.Objects;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanConstructorInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.common.util.ArrayUtils;
import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;

public final class PrivateControlWriterMBean implements DynamicMBean {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(PrivateControlWriterMBean.class);

    private static final String OP_APPLY = "applySnapshot";
    private static final String OP_READ = "readAcceptedComplete";
    private static final String ATTEMPT_ID_KEY = "attemptId";
    private static final String FALLBACK_ATTEMPT_ID = "";

    private static final MBeanInfo INFO = buildMBeanInfo();

    private final TracingSettingsUpdater updater;
    private final TracingSettingsStore store;
    private final PrivateControlOpenTypeCodec codec;

    public PrivateControlWriterMBean(TracingSettingsUpdater updater, TracingSettingsStore store) {
        this(Objects.requireNonNull(updater, "updater"),
                Objects.requireNonNull(store, "store"),
                new PrivateControlOpenTypeCodec());
        PrivateControlWireTypes.initialize();
    }

    PrivateControlWriterMBean(TracingSettingsUpdater updater,
                              TracingSettingsStore store,
                              PrivateControlOpenTypeCodec codec) {
        this.updater = updater;
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
    public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException {
        if (isReadAcceptedComplete(actionName, params, signature)) {
            return handleReadAcceptedComplete();
        }

        if (isApplySnapshot(actionName, params, signature)) {
            return handleApplySnapshot((CompositeData) params[0]);
        }

        throw new ReflectionException(new NoSuchMethodException(actionName));
    }

    private Object handleReadAcceptedComplete() {
        TracingSettingsRevision snapshot = store.current();
        return codec.encodeAcceptedComplete(snapshot);
    }

    private Object handleApplySnapshot(CompositeData request) throws MBeanException {
        String wireAttemptId = extractAttemptId(request);
        DecodedSettingsUpdate decoded;

        try {
            decoded = codec.decode(request);
        } catch (PrivateControlDecodeException exception) {
            SettingsUpdateResult rejected = updater.rejectBeforeApply(wireAttemptId, exception.messageCode());
            return encodePreApplyResult(rejected, wireAttemptId);
        } catch (RuntimeException exception) {
            log.warn(AgentEventCodes.CTRL_WRITER, "Private control writer failed", exception, "attemptId", "<unavailable>");
            SettingsUpdateResult failed = updater.failBeforeApply(wireAttemptId, SettingsRejectionReason.INTERNAL_FAILURE);
            return encodePreApplyResult(failed, wireAttemptId);
        }

        String attemptId = decoded.attempt().attemptId();
        SettingsUpdateResult result;

        try {
            result = updater.apply(decoded.attempt(), decoded.expectedBase(), decoded.settings());
        } catch (RuntimeException exception) {
            log.warn(AgentEventCodes.CTRL_WRITER, "Private control writer failed", exception, "attemptId", attemptId);
            throw new MBeanException(exception, "Settings snapshot update failed");
        }

        try {
            return codec.encode(result, attemptId);
        } catch (RuntimeException exception) {
            log.warn(AgentEventCodes.CTRL_WRITER, "Private control writer failed", exception, "attemptId", attemptId);
            throw new MBeanException(exception, "Settings snapshot response encoding failed");
        }
    }

    private Object encodePreApplyResult(SettingsUpdateResult result, String wireAttemptId) throws MBeanException {
        try {
            return codec.encode(result, wireAttemptId);
        } catch (RuntimeException exception) {
            log.warn(AgentEventCodes.CTRL_WRITER, "Private control writer failed", exception, "attemptId", "<unavailable>");
            throw new MBeanException(exception, "Pre-apply settings response encoding failed");
        }
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return INFO;
    }

    private static MBeanInfo buildMBeanInfo() {
        return new MBeanInfo(
                PrivateControlWriterMBean.class.getName(),
                "Strict complete-snapshot private writer and accepted-policy reader",
                new MBeanAttributeInfo[0],
                new MBeanConstructorInfo[0],
                buildOperationInfo(),
                new MBeanNotificationInfo[0]
        );
    }

    private static MBeanOperationInfo[] buildOperationInfo() {
        return new MBeanOperationInfo[]{
                buildApplySnapshotOperation(),
                buildReadAcceptedCompleteOperation()
        };
    }

    private static MBeanOperationInfo buildApplySnapshotOperation() {
        String requestSchema = PrivateControlWireTypes.REQUEST.getTypeName();
        MBeanParameterInfo requestParameter = new MBeanParameterInfo(
                "request",
                CompositeData.class.getName(),
                "CompositeData conforming to " + requestSchema + " OpenType schema."
        );

        String description = "Applies one complete Category A snapshot from the control plane. Requires %s CompositeData."
                .formatted(requestSchema);

        return new MBeanOperationInfo(
                OP_APPLY,
                description,
                new MBeanParameterInfo[]{requestParameter},
                CompositeData.class.getName(),
                MBeanOperationInfo.ACTION
        );
    }

    private static MBeanOperationInfo buildReadAcceptedCompleteOperation() {
        String acceptedSchema = PrivateControlWireTypes.ACCEPTED_COMPLETE.getTypeName();
        String description = "Returns the immutable accepted-policy snapshot (%s)."
                .formatted(acceptedSchema);

        return new MBeanOperationInfo(
                OP_READ,
                description,
                new MBeanParameterInfo[0],
                CompositeData.class.getName(),
                MBeanOperationInfo.INFO
        );
    }

    private static boolean isReadAcceptedComplete(String actionName, Object[] params, String[] signature) {
        return OP_READ.equals(actionName) && ArrayUtils.isNullOrEmpty(params) && ArrayUtils.isNullOrEmpty(signature);
    }

    private static boolean isApplySnapshot(String actionName, Object[] params, String[] signature) {
        return OP_APPLY.equals(actionName)
                && params != null && params.length == 1 && params[0] instanceof CompositeData
                && signature != null && signature.length == 1 && CompositeData.class.getName().equals(signature[0]);
    }

    private static String extractAttemptId(CompositeData request) {
        if (request == null || !request.containsKey(ATTEMPT_ID_KEY)) {
            return FALLBACK_ATTEMPT_ID;
        }

        Object value = request.get(ATTEMPT_ID_KEY);
        return ((value instanceof String text) && StringUtils.hasText(text)) ? text : FALLBACK_ATTEMPT_ID;
    }
}
