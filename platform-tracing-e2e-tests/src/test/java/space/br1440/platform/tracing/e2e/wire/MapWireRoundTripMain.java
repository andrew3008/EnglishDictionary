package space.br1440.platform.tracing.e2e.wire;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.e2e.extension.jmx.wire.WireRoundTripTestMBean;
import space.br1440.platform.tracing.e2e.extension.jmx.wire.WireRoundTripTestMarkers;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * E2E wire round-trip main: App CL builds Map wire payloads and invokes the test-only JMX MBean
 * through the in-process {@link MBeanServer}. The MBean is available only because the test-only
 * {@code jmxWireExtension} JAR was loaded through {@code -Dotel.javaagent.extensions}, so its
 * implementation resolves in the Agent {@code ExtensionClassLoader}.
 */
public final class MapWireRoundTripMain {

    private static final String READY_MARKER = "READY";
    private static final Duration MBEAN_WAIT = Duration.ofSeconds(30);

    private MapWireRoundTripMain() {
    }

    public static void main(String[] args) throws Exception {
        awaitWireMBeanRegistered();
        runScenario("validRoundTrip", validPayload());
        runScenario("invalidType", invalidTypePayload());
        runScenario("unknownKey", unknownKeyPayload());
        runScenario("topologyField", topologyPayload());
        runScenario("rawDto", rawDtoPayload());
        runScenario("unsupportedContractVersion", unsupportedVersionPayload());
        System.out.println(READY_MARKER);
        System.out.flush();
    }

    private static void awaitWireMBeanRegistered() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(WireRoundTripTestMBean.OBJECT_NAME);
        long deadline = System.nanoTime() + MBEAN_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (server.isRegistered(name)) {
                WireRoundTripTestMarkers.emit("awaitMBean=registered");
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new IllegalStateException("Wire test MBean not registered: " + WireRoundTripTestMBean.OBJECT_NAME);
    }

    private static void runScenario(String scenario, Map<String, Object> payload) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(WireRoundTripTestMBean.OBJECT_NAME);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) server.invoke(
                    name,
                    WireRoundTripTestMBean.OP_EVALUATE_WIRE_PAYLOAD,
                    new Object[]{payload},
                    new String[]{Map.class.getName()});

            boolean valid = Boolean.TRUE.equals(result.get(WireRoundTripTestMBean.RESULT_VALID));
            Object violationCountObj = result.get(WireRoundTripTestMBean.RESULT_VIOLATION_COUNT);
            int violationCount = violationCountObj instanceof Integer i ? i : -1;
            String firstKey = String.valueOf(result.get(WireRoundTripTestMBean.RESULT_FIRST_VIOLATION_KEY));

            WireRoundTripTestMarkers.emitScenarioResult(
                    scenario,
                    new WireRoundTripTestMarkers.ScenarioResult(valid, violationCount, firstKey, ""));
        } catch (Throwable t) {
            WireRoundTripTestMarkers.emitScenarioResult(
                    scenario,
                    new WireRoundTripTestMarkers.ScenarioResult(false, -1, "",
                            t.getClass().getName()));
        }
    }

    private static Map<String, Object> validPayload() {
        Map<String, Object> payload = basePayload(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY);
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);
        payload.put(TracingControlProtocolKeys.DIAGNOSTICS_REQUEST_ID, "e2e-wire-req");
        return payload;
    }

    private static Map<String, Object> invalidTypePayload() {
        Map<String, Object> payload = basePayload(TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY);
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, "0.5");
        return payload;
    }

    private static Map<String, Object> unknownKeyPayload() {
        Map<String, Object> payload = basePayload(TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY);
        payload.put("unknown.key", true);
        return payload;
    }

    private static Map<String, Object> topologyPayload() {
        Map<String, Object> payload = basePayload(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY);
        payload.put("exporter.endpoint", "http://collector:4318");
        return payload;
    }

    private static Map<String, Object> rawDtoPayload() {
        Map<String, Object> payload = basePayload(TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY);
        payload.put(TracingControlProtocolKeys.SOURCE, new AppSideWireDto());
        return payload;
    }

    private static Map<String, Object> unsupportedVersionPayload() {
        Map<String, Object> payload = basePayload(TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY);
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 99);
        return payload;
    }

    private static Map<String, Object> basePayload(TracingControlProtocolOperation operation) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION, operation.wireValue());
        return payload;
    }

    /** App CL test DTO — must not cross wire boundary successfully. */
    public static final class AppSideWireDto {
        @SuppressWarnings("unused")
        private final String marker = "e2e-app-dto";
    }
}
