package space.br1440.platform.tracing.e2e.extension.jmx.wire;

import java.util.Map;

/**
 * Test-only JMX contract proving validated Map wire evaluation across the classloader boundary.
 * <p>
 * <b>Not production code.</b> This MBean exists only inside the test-only {@code jmxWireExtension}
 * source set, is packaged into a dedicated test-only OTel extension JAR, and is loaded by E2E via
 * {@code -Dotel.javaagent.extensions}. It replaces the former production
 * {@code TracingControlWireSpikeMBean}.
 */
public interface WireRoundTripTestMBean {

    /** Test-only {@code ObjectName} — distinct from any production JMX control surface. */
    String OBJECT_NAME = "space.br1440.platform.tracing.e2e:type=WireRoundTripTest,name=WireRoundTripTest";

    String OP_EVALUATE_WIRE_PAYLOAD = "evaluateWirePayload";

    // -- OpenMBean-compatible result keys (returned Map) ----------------------------------------

    String RESULT_VALID = "valid";
    String RESULT_VIOLATION_COUNT = "violationCount";
    String RESULT_FIRST_VIOLATION_KEY = "firstViolationKey";
    String RESULT_FIRST_VIOLATION_REASON = "firstViolationReason";
    String RESULT_CONTRACT_VERSION = "contractVersion";
    String RESULT_AGENT_API_CLASS = "agentApiValidatorClass";

    /**
     * Validates {@code payload} using {@code TracingControlWireValidator} from {@code platform-tracing-api}.
     * <p>
     * Does <b>not</b> mutate sampler/scrubbing/export runtime state — validation-only harness.
     *
     * @param payload wire Map from application classloader caller
     * @return OpenMBean-compatible result Map (never throws for normal validation failure)
     */
    Map<String, Object> evaluateWirePayload(Map<String, Object> payload);
}
