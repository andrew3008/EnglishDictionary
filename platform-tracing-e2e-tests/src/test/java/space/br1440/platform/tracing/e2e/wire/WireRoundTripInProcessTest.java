package space.br1440.platform.tracing.e2e.wire;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.validation.TracingControlProtocolValidator;
import space.br1440.platform.tracing.e2e.extension.jmx.wire.WireRoundTripTestMBean;
import space.br1440.platform.tracing.e2e.extension.jmx.wire.WireRoundTripTestMBeanImpl;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-process JMX round-trip over the test-only wire harness: an OpenMBean-compatible Map crosses
 * {@link MBeanServer#invoke} and returns a structured validation result from
 * {@link TracingControlProtocol#current()} validator.
 * <p>
 * Preserves the assertions of the former production-coupled unit tests (the removed
 * {@code jmx.spike} round-trip tests) without any production {@code jmx.spike} dependency.
 * Cross-classloader fidelity is additionally covered by {@link MapWireRoundTripE2ETest}.
 */
@DisplayName("Wire round-trip: in-process JMX Map boundary")
class WireRoundTripInProcessTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private ObjectName name;

    @BeforeEach
    void registerMBean() throws Exception {
        WireRoundTripTestMBeanImpl.registerSafely();
        name = new ObjectName(WireRoundTripTestMBean.OBJECT_NAME);
        assertThat(server.isRegistered(name)).isTrue();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (name != null && server.isRegistered(name)) {
            server.unregisterMBean(name);
        }
    }

    @Test
    @DisplayName("valid Map payload round-trips as valid")
    void validRoundTrip() throws Exception {
        Map<String, Object> payload = minimalValidPayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);
        payload.put(TracingControlProtocolKeys.DIAGNOSTICS_REQUEST_ID, "round-trip-1");

        Map<String, Object> result = invokeEvaluate(payload);

        assertThat(result.get(WireRoundTripTestMBean.RESULT_VALID)).isEqualTo(true);
        assertThat(result.get(WireRoundTripTestMBean.RESULT_VIOLATION_COUNT)).isEqualTo(0);
        assertThat(result.get(WireRoundTripTestMBean.RESULT_CONTRACT_VERSION)).isEqualTo(1);
        assertThat(result.get(WireRoundTripTestMBean.RESULT_AGENT_API_CLASS))
                .isEqualTo(TracingControlProtocolValidator.class.getName());
    }

    @Test
    @DisplayName("invalid sampling.ratio type rejected without ClassCastException")
    void invalidTypeRejected() throws Exception {
        Map<String, Object> payload = minimalValidPayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, "0.5");

        Map<String, Object> result = invokeEvaluate(payload);

        assertThat(result.get(WireRoundTripTestMBean.RESULT_VALID)).isEqualTo(false);
        assertThat(result.get(WireRoundTripTestMBean.RESULT_VIOLATION_COUNT)).isNotNull();
    }

    @Test
    @DisplayName("unknown key rejected (strict v1)")
    void unknownKeyRejected() throws Exception {
        Map<String, Object> payload = minimalValidPayload();
        payload.put("unknown.key", true);

        Map<String, Object> result = invokeEvaluate(payload);

        assertThat(result.get(WireRoundTripTestMBean.RESULT_VALID)).isEqualTo(false);
        assertThat(result.get(WireRoundTripTestMBean.RESULT_FIRST_VIOLATION_KEY)).isEqualTo("unknown.key");
    }

    @Test
    @DisplayName("SpanRelationship fields rejected for runtime apply")
    void topologyFieldRejected() throws Exception {
        Map<String, Object> payload = minimalValidPayload();
        payload.put(TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT, "http://collector:4318");
        payload.put(TracingControlProtocolKeys.TOPOLOGY_QUEUE_SIZE, 10_000);
        payload.put(TracingControlProtocolKeys.TOPOLOGY_SDK_MODE, "agent");

        Map<String, Object> result = invokeEvaluate(payload);

        assertThat(result.get(WireRoundTripTestMBean.RESULT_VALID)).isEqualTo(false);
    }

    @Test
    @DisplayName("raw App-side DTO in Map rejected safely")
    void rawDtoRejected() throws Exception {
        Map<String, Object> payload = minimalValidPayload();
        payload.put(TracingControlProtocolKeys.SOURCE, new AppSideWireDto());

        Map<String, Object> result = invokeEvaluate(payload);

        assertThat(result.get(WireRoundTripTestMBean.RESULT_VALID)).isEqualTo(false);
    }

    @Test
    @DisplayName("unsupported contractVersion rejected")
    void unsupportedContractVersionRejected() throws Exception {
        Map<String, Object> payload = minimalValidPayload();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 2);

        Map<String, Object> result = invokeEvaluate(payload);

        assertThat(result.get(WireRoundTripTestMBean.RESULT_VALID)).isEqualTo(false);
        assertThat(result.get(WireRoundTripTestMBean.RESULT_FIRST_VIOLATION_KEY))
                .isEqualTo(TracingControlProtocolKeys.CONTRACT_VERSION);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeEvaluate(Map<String, Object> payload) throws Exception {
        Object raw = server.invoke(
                name,
                WireRoundTripTestMBean.OP_EVALUATE_WIRE_PAYLOAD,
                new Object[]{payload},
                new String[]{Map.class.getName()});
        assertThat(raw).isInstanceOf(Map.class);
        return (Map<String, Object>) raw;
    }

    private static Map<String, Object> minimalValidPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION, TracingControlProtocolKeys.OPERATION_VALIDATE_RUNTIME_POLICY);
        return payload;
    }

    /** Test-only DTO simulating App CL custom type — must not cross wire boundary. */
    static final class AppSideWireDto {
        @SuppressWarnings("unused")
        private final String marker = "app-cl-dto";
    }
}
