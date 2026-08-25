package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;

/**
 * Real cross-module Agent/Spring parity test (P4 audit checklist item #10).
 * <p>
 * Co-located with the two cross-module codec parity tests in the Agent protocol test package.
 * It calls the real Agent {@code TracingSettingsDigest.sha256(TracingSettings)} through the
 * existing {@code testImplementation project(':platform-starters-tracing:platform-tracing-otel-javaagent-extension')}
 * dependency; no digest algorithm or wire schema is duplicated in this test.
 * <p>
 * Verifies, for several representative policies, that the Spring-side
 * {@code control.jmx.protocol.CandidateDigest} (a hand-ported mirror, necessary because Spring
 * has no compile-time or runtime dependency on Agent-internal classes in production) produces
 * byte-for-byte identical output to the real Agent algorithm. If this test ever fails, the
 * Spring-side mirror has drifted and every {@code applySnapshot} call will be rejected by the
 * Agent with {@code INVALID_VALUE} ("candidateDigest does not match policy").
 */
class CandidateDigestAgentParityTest {

    @Test
    void matchesAgentDigestForDefaultPolicy() throws Exception {
        assertParity(TracingSettings.of(
                true, 1.0, Map.of(),
                true, false, true, true, TracingLogLevel.INFO));
    }

    @Test
    void matchesAgentDigestForPolicyWithRouteRatios() throws Exception {
        assertParity(TracingSettings.of(
                true, 0.25, Map.of("/checkout", 0.5, "/search", 0.3),
                true, true, false, false, TracingLogLevel.DEBUG));
    }

    @Test
    void matchesAgentDigestForAllLogLevels() throws Exception {
        for (TracingLogLevel level : TracingLogLevel.values()) {
            assertParity(TracingSettings.of(
                    false, 0.0, Map.of(),
                    false, false, false, false, level));
        }
    }

    @Test
    void matchesAgentDigestWhenNegativeZeroIsCanonicalized() throws Exception {
        // Agent normalizeRatio и Spring CompleteTracingSettings канонизируют -0.0 → +0.0
        // до digest; паритет обязан сохраняться.
        assertParity(TracingSettings.of(
                true, -0.0d, Map.of("/z", -0.0d),
                true, false, true, true, TracingLogLevel.INFO));
    }

    private static void assertParity(TracingSettings agentPolicy) throws Exception {
        String agentDigest = TracingSettingsDigest.sha256(agentPolicy);

        CompleteTracingSettings springPolicy = CompleteTracingSettings.builder()
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
                .as("Spring CandidateDigest must byte-for-byte match Agent TracingSettingsDigest")
                .isEqualTo(agentDigest);
    }

    /**
     * {@code CandidateDigest} is package-private in {@code jmx.privatecontrol}; reached here via
     * reflection rather than widening its visibility for a single test.
     */
    private static String invokeSpringCandidateDigest(CompleteTracingSettings policy) throws Exception {
        Class<?> clazz = Class.forName(
                "space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.CandidateDigest");
        Method method = clazz.getDeclaredMethod("sha256", CompleteTracingSettings.class);
        method.setAccessible(true);
        return (String) method.invoke(null, policy);
    }
}
