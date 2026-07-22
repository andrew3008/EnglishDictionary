package space.br1440.platform.tracing.e2e.extension.jmx.wire;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolDecodeResult;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolViolation;
import space.br1440.platform.tracing.otel.control.protocol.RuntimePolicyControlDomainValidator;
import space.br1440.platform.tracing.otel.control.protocol.TracingControlDomainValidationResult;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only agent-side implementation of {@link WireRoundTripTestMBean}.
 * <p>
 * Delegates validation to {@link TracingControlProtocol#current()} validator — does not duplicate
 * validator logic and does not mutate sampler/export/scrubbing runtime.
 */
public final class WireRoundTripTestMBeanImpl implements WireRoundTripTestMBean {

    @Override
    public Map<String, Object> evaluateWirePayload(Map<String, Object> payload) {
        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);
        TracingControlDomainValidationResult domainResult = validateDomainIfNeeded(result);
        boolean valid = result.valid() && domainResult.valid();
        int violationCount = result.violations().size() + domainResult.violations().size();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put(RESULT_VALID, valid);
        response.put(RESULT_VIOLATION_COUNT, violationCount);
        response.put(RESULT_AGENT_API_CLASS, TracingControlProtocol.class.getName());

        if (valid) {
            response.put(RESULT_CONTRACT_VERSION,
                    result.normalizedPayload().get(TracingControlProtocolKeys.CONTRACT_VERSION));
            response.put(RESULT_FIRST_VIOLATION_KEY, "");
            response.put(RESULT_FIRST_VIOLATION_REASON, "");
        } else if (!result.valid()) {
            TracingControlProtocolViolation first = result.violations().getFirst();
            response.put(RESULT_FIRST_VIOLATION_KEY, nullToEmpty(first.key()));
            response.put(RESULT_FIRST_VIOLATION_REASON, nullToEmpty(first.reason()));
        } else {
            response.put(RESULT_FIRST_VIOLATION_KEY, "domain");
            response.put(RESULT_FIRST_VIOLATION_REASON, nullToEmpty(domainResult.violations().getFirst()));
        }
        return response;
    }

    private static TracingControlDomainValidationResult validateDomainIfNeeded(TracingControlProtocolDecodeResult result) {
        if (!result.valid()) {
            return TracingControlDomainValidationResult.success();
        }
        if (result.operation().orElseThrow() == TracingControlProtocolOperation.READ_APPLIED_STATE) {
            return TracingControlDomainValidationResult.success();
        }
        return RuntimePolicyControlDomainValidator.validate(result.normalizedPayload());
    }

    /**
     * Registers the test-only MBean; idempotent (second call returns {@code false}).
     */
    public static boolean registerSafely() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName objectName = new ObjectName(OBJECT_NAME);
            try {
                StandardMBean mbean = new StandardMBean(new WireRoundTripTestMBeanImpl(), WireRoundTripTestMBean.class);
                server.registerMBean(mbean, objectName);
                WireRoundTripTestMarkers.emit("registered=true");
                WireRoundTripTestMarkers.emit("objectName=" + OBJECT_NAME);
                return true;
            } catch (InstanceAlreadyExistsException e) {
                WireRoundTripTestMarkers.emit("registered=false");
                return false;
            }
        } catch (Throwable t) {
            WireRoundTripTestMarkers.emit("registerError=" + t.getClass().getName());
            return false;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
