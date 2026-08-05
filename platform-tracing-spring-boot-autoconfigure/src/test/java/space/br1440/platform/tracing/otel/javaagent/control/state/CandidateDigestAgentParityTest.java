package space.br1440.platform.tracing.otel.javaagent.control.state;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real cross-module Agent/Spring parity test (P4 audit checklist item #10).
 * <p>
 * Deliberately declared in the same package name as the Agent-internal
 * {@code control.state} classes so that this test-scope-only class can call the
 * package-private {@code EffectiveTracingPolicyDigest.sha256(EffectiveTracingPolicy)} directly
 * — no production API is made public, no schema is hand-duplicated from documentation. This
 * relies on {@code testImplementation project(':platform-tracing-otel-javaagent-extension')}
 * already declared in this module's build.gradle.
 * <p>
 * Verifies, for several representative policies, that the Spring-side
 * {@code jmx.privatecontrol.CandidateDigest} (a hand-ported mirror, necessary because Spring
 * has no compile-time or runtime dependency on Agent-internal classes in production) produces
 * byte-for-byte identical output to the real Agent algorithm. If this test ever fails, the
 * Spring-side mirror has drifted and every {@code applySnapshot} call will be rejected by the
 * Agent with {@code INVALID_VALUE} ("candidateDigest does not match policy").
 */
class CandidateDigestAgentParityTest {

    @Test
    void matchesAgentDigestForDefaultPolicy() throws Exception {
        assertParity(EffectiveTracingPolicy.of(
                true, 1.0, Map.of(),
                true, false, true, true, PolicyLogLevel.INFO));
    }

    @Test
    void matchesAgentDigestForPolicyWithRouteRatios() throws Exception {
        assertParity(EffectiveTracingPolicy.of(
                true, 0.25, Map.of("/checkout", 0.5, "/search", 0.3),
                true, true, false, false, PolicyLogLevel.DEBUG));
    }

    @Test
    void matchesAgentDigestForAllLogLevels() throws Exception {
        for (PolicyLogLevel level : PolicyLogLevel.values()) {
            assertParity(EffectiveTracingPolicy.of(
                    false, 0.0, Map.of(),
                    false, false, false, false, level));
        }
    }

    @Test
    void matchesAgentDigestWhenNegativeZeroIsCanonicalized() throws Exception {
        // Agent normalizeRatio и Spring CompleteTracingPolicySnapshot канонизируют -0.0 → +0.0
        // до digest; паритет обязан сохраняться.
        assertParity(EffectiveTracingPolicy.of(
                true, -0.0d, Map.of("/z", -0.0d),
                true, false, true, true, PolicyLogLevel.INFO));
    }

    private static void assertParity(EffectiveTracingPolicy agentPolicy) throws Exception {
        String agentDigest = EffectiveTracingPolicyDigest.sha256(agentPolicy);

        CompleteTracingPolicySnapshot springPolicy = CompleteTracingPolicySnapshot.builder()
                .samplingEnabled(agentPolicy.samplingEnabled())
                .samplingRatio(agentPolicy.samplingRatio())
                .samplingRouteRatios(agentPolicy.samplingRouteRatios())
                .validationEnabled(agentPolicy.validationEnabled())
                .validationStrict(agentPolicy.validationStrict())
                .exporterEnabled(agentPolicy.exporterEnabled())
                .traceControlHeadersEnabled(agentPolicy.traceControlHeadersEnabled())
                .diagnosticsLogLevel(agentPolicy.diagnosticsLogLevel().name())
                .build();
        String springDigest = invokeSpringCandidateDigest(springPolicy);

        assertThat(springDigest)
                .as("Spring CandidateDigest must byte-for-byte match Agent EffectiveTracingPolicyDigest")
                .isEqualTo(agentDigest);
    }

    /**
     * {@code CandidateDigest} is package-private in {@code jmx.privatecontrol}; reached here via
     * reflection rather than widening its visibility for a single test.
     */
    private static String invokeSpringCandidateDigest(CompleteTracingPolicySnapshot policy) throws Exception {
        Class<?> clazz = Class.forName(
                "space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.CandidateDigest");
        Method method = clazz.getDeclaredMethod("sha256", CompleteTracingPolicySnapshot.class);
        method.setAccessible(true);
        return (String) method.invoke(null, policy);
    }
}
