package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import java.util.Objects;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanConstructorInfo;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;
import javax.management.openmbean.OpenMBeanOperationInfoSupport;
import javax.management.openmbean.OpenMBeanParameterInfo;
import javax.management.openmbean.OpenMBeanParameterInfoSupport;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ControlPlaneLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public final class PlatformSettingsMutationMBean implements DynamicMBean {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(PlatformSettingsMutationMBean.class);

    private static final String OP_APPLY = JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT;
    private static final String FALLBACK_ATTEMPT_ID = "";
    private static final String ATTEMPT_ID_UNAVAILABLE = "<unavailable>";
    private static final MBeanInfo INFO = buildMBeanInfo();

    private final TracingSettingsUpdater updater;
    private final JmxControlOpenTypeCodec codec;

    public PlatformSettingsMutationMBean(TracingSettingsUpdater updater) {
        this(updater, new JmxControlOpenTypeCodec());
    }

    PlatformSettingsMutationMBean(TracingSettingsUpdater updater,
                                  JmxControlOpenTypeCodec codec) {
        this.updater = Objects.requireNonNull(updater, "updater");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public Object getAttribute(String attribute) throws AttributeNotFoundException {
        throw new AttributeNotFoundException("JMX settings mutation endpoint has no attributes");
    }

    @Override
    public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
        throw new AttributeNotFoundException("JMX settings mutation endpoint has no writable attributes");
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
    public Object invoke(String actionName,
                         Object[] params,
                         String[] signature) throws MBeanException, ReflectionException {
        if (isApplySnapshot(actionName, params, signature)) {
            return handleApplySnapshot((CompositeData) params[0]);
        }

        NoSuchMethodException rootCause = new NoSuchMethodException("Unsupported JMX settings mutation operation");
        throw new ReflectionException(rootCause);
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return INFO;
    }

    private Object handleApplySnapshot(CompositeData request) throws MBeanException {
        String wireAttemptId = FALLBACK_ATTEMPT_ID;
        DecodedSettingsUpdate decoded;
        try {
            wireAttemptId = extractAttemptId(request);
            decoded = codec.decode(request);
        } catch (JmxControlDecodeException exception) {
            logPolicyRejection(exception, wireAttemptId);
            return encodePreApplyResult(
                    responseAttemptId(wireAttemptId),
                    exception.messageCode(),
                    false);
        } catch (RuntimeException exception) {
            logFailure(exception, "decode", FALLBACK_ATTEMPT_ID);
            return encodePreApplyResult(
                    responseAttemptId(wireAttemptId),
                    SettingsRejectionReason.INTERNAL_FAILURE,
                    true);
        }

        String attemptId = decoded.attempt().attemptId();
        SettingsUpdateResult result;
        try {
            result = updater.apply(decoded.attempt(), decoded.expectedBase(), decoded.settings());
        } catch (RuntimeException exception) {
            logFailure(exception, "apply", attemptId);
            throw neutralFailure(
                    "JMX_CONTROL_APPLY_FAILED",
                    "Settings snapshot update failed");
        }

        try {
            return codec.encode(result, attemptId);
        } catch (RuntimeException exception) {
            logFailure(exception, "response-encoding", attemptId);
            throw neutralFailure(
                    "JMX_CONTROL_RESPONSE_ENCODING_FAILED",
                    "Settings snapshot response encoding failed");
        }
    }

    private Object encodePreApplyResult(String wireAttemptId,
                                        SettingsRejectionReason reason,
                                        boolean failed) throws MBeanException {
        try {
            SettingsUpdateResult result = failed
                    ? updater.failBeforeApply(reason)
                    : updater.rejectBeforeApply(reason);
            return codec.encode(result, wireAttemptId);
        } catch (RuntimeException exception) {
            logFailure(exception, "pre-apply-response", wireAttemptId);
            throw neutralFailure(
                    "JMX_CONTROL_PRE_APPLY_ENCODING_FAILED",
                    "Pre-apply settings response encoding failed"
            );
        }
    }

    private static String extractAttemptId(CompositeData request) {
        String key = JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID;
        if (request == null || !request.containsKey(key)) {
            return FALLBACK_ATTEMPT_ID;
        }

        Object value = request.get(key);
        return value instanceof String text && StringUtils.hasText(text)
                ? text
                : FALLBACK_ATTEMPT_ID;
    }

    private static String responseAttemptId(String wireAttemptId) {
        return SettingsUpdateAttempt.isCanonicalAttemptId(wireAttemptId)
                ? wireAttemptId
                : FALLBACK_ATTEMPT_ID;
    }

    private static boolean isApplySnapshot(String actionName,
                                           Object[] params,
                                           String[] signature) {
        return OP_APPLY.equals(actionName)
                && params != null
                && params.length == 1
                && params[0] instanceof CompositeData
                && signature != null
                && signature.length == 1
                && CompositeData.class.getName().equals(signature[0]);
    }

    private static MBeanInfo buildMBeanInfo() {
        OpenMBeanParameterInfo request = new OpenMBeanParameterInfoSupport(
                JmxWireContract.PARAMETER_SETTINGS_MUTATION_APPLY_SNAPSHOT_REQUEST,
                "Complete tracing settings snapshot",
                JmxOpenTypes.APPLY_SNAPSHOT_REQUEST
        );

        OpenMBeanOperationInfo apply = new OpenMBeanOperationInfoSupport(
                OP_APPLY,
                "Applies one complete tracing settings snapshot",
                new OpenMBeanParameterInfo[]{request},
                JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE,
                MBeanOperationInfo.ACTION
        );

        return new OpenMBeanInfoSupport(
                PlatformSettingsMutationMBean.class.getName(),
                "Local tracing settings mutation endpoint",
                new OpenMBeanAttributeInfo[0],
                new OpenMBeanConstructorInfo[0],
                new OpenMBeanOperationInfo[]{apply},
                new MBeanNotificationInfo[0]
        );
    }

    private static void logFailure(RuntimeException failure, String phase, String attemptId) {
        log.log(ControlPlaneLogEvent.JMX_SETTINGS_MUTATION_FAILED, fields -> fields
                .putString(AgentLogField.PHASE, phase)
                .putString(AgentLogField.ATTEMPT_ID, sanitizeAttemptIdForLog(attemptId))
                .cause(failure));
    }

    private static void logPolicyRejection(JmxControlDecodeException rejection, String attemptId) {
        if (!isPolicyRejection(rejection.messageCode())) {
            return;
        }

        log.log(ControlPlaneLogEvent.JMX_SETTINGS_POLICY_REJECTED, fields -> fields
                .putString(AgentLogField.PHASE, "decode")
                .putString(AgentLogField.ATTEMPT_ID, sanitizeAttemptIdForLog(attemptId))
                .putEnum(AgentLogField.MESSAGE_CODE, rejection.messageCode())
                .putString(AgentLogField.PROPERTY_NAME, rejection.propertyName()));
    }

    private static boolean isPolicyRejection(SettingsRejectionReason reason) {
        return switch (reason) {
            case INVALID_SAMPLING_RATIO,
                 INVALID_ROUTE_RATIO,
                 INVALID_VALIDATION_LEVEL,
                 INVALID_DIAGNOSTICS_LOG_LEVEL -> true;
            default -> false;
        };
    }

    private static String sanitizeAttemptIdForLog(String attemptId) {
        return SettingsUpdateAttempt.isCanonicalAttemptId(attemptId)
                ? attemptId
                : ATTEMPT_ID_UNAVAILABLE;
    }

    private static MBeanException neutralFailure(String code, String message) {
        return new MBeanException(null, code + ": " + message);
    }
}
