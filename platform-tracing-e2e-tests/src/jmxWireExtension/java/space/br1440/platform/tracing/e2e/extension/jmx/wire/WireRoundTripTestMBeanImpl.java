package space.br1440.platform.tracing.e2e.extension.jmx.wire;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.result.TracingControlProtocolValidationResult;
import space.br1440.platform.tracing.api.control.protocol.result.TracingControlProtocolViolation;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.validation.TracingControlProtocolValidator;

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

    private static final TracingControlProtocolValidator VALIDATOR = TracingControlProtocol.current().validator();

    @Override
    public Map<String, Object> evaluateWirePayload(Map<String, Object> payload) {
        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(RESULT_VALID, result.valid());
        response.put(RESULT_VIOLATION_COUNT, result.violations().size());
        response.put(RESULT_AGENT_API_CLASS, VALIDATOR.getClass().getName());

        if (result.valid()) {
            response.put(RESULT_CONTRACT_VERSION,
                    result.normalizedPayload().get(TracingControlProtocolKeys.CONTRACT_VERSION));
            response.put(RESULT_FIRST_VIOLATION_KEY, "");
            response.put(RESULT_FIRST_VIOLATION_REASON, "");
        } else {
            TracingControlProtocolViolation first = result.violations().getFirst();
            response.put(RESULT_FIRST_VIOLATION_KEY, nullToEmpty(first.key()));
            response.put(RESULT_FIRST_VIOLATION_REASON, nullToEmpty(first.reason()));
        }
        return response;
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
