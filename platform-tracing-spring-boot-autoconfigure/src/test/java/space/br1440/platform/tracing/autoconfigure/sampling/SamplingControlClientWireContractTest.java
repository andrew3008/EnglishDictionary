package space.br1440.platform.tracing.autoconfigure.sampling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolDecodeResult;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Application-side JMX client wire contract: {@link PlatformTracingJmxClient}-style caller invokes
 * Map wire evaluation through {@link MBeanServer#invoke} without importing otel-extension types.
 * <p>
 * Uses a test-only MBean stub delegating to {@link TracingControlProtocol#current()} validator — proves App CL can
 * build/invoke Map payloads safely against the wire contract (PR-10+).
 */
@DisplayName("PlatformTracingJmxClient Map wire contract")
class SamplingControlClientWireContractTest {

    private static final String STUB_OBJECT_NAME =
            "space.br1440.platform.test:type=WireContractStub,name=PlatformTracingJmxClientWireContract";

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private ObjectName objectName;

    @BeforeEach
    void registerStub() throws Exception {
        objectName = new ObjectName(STUB_OBJECT_NAME);
        server.registerMBean(new StandardMBean(new WireEvaluateStub(), WireEvaluateStubMBean.class), objectName);
    }

    @AfterEach
    void unregisterStub() throws Exception {
        if (objectName != null && server.isRegistered(objectName)) {
            server.unregisterMBean(objectName);
        }
    }

    @Test
    @DisplayName("invoke with valid Map returns valid=true without ClassCastException")
    void validMapInvoke() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY.wireValue());
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);

        Map<String, Object> result = invokeEvaluate(payload);

        assertThat(result.get("valid")).isEqualTo(true);
    }

    @Test
    @DisplayName("invoke with raw DTO value rejected safely")
    void rawDtoInvokeRejected() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY.wireValue());
        payload.put(TracingControlProtocolKeys.SOURCE, new AppSideDto());

        Map<String, Object> result = invokeEvaluate(payload);

        assertThat(result.get("valid")).isEqualTo(false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeEvaluate(Map<String, Object> payload) throws Exception {
        Object raw = server.invoke(objectName, "evaluateWirePayload",
                new Object[]{payload}, new String[]{Map.class.getName()});
        return (Map<String, Object>) raw;
    }

    public interface WireEvaluateStubMBean {
        Map<String, Object> evaluateWirePayload(Map<String, Object> payload);
    }

    static final class WireEvaluateStub implements WireEvaluateStubMBean {
        @Override
        public Map<String, Object> evaluateWirePayload(Map<String, Object> payload) {
            TracingControlProtocolDecodeResult validation = TracingControlProtocol.current().decode(payload);
            Map<String, Object> result = new HashMap<>();
            result.put("valid", validation.valid());
            result.put("violationCount", validation.violations().size());
            return result;
        }
    }

    static final class AppSideDto {
        @SuppressWarnings("unused")
        private final String marker = "autoconfigure-test-dto";
    }
}
