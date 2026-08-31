package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxTracingSettingsDigest;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;

final class TracingSettingsDigestAgentParityTest {

    @Test
    void matchesAgentDigestForDefaultPolicy() {
        assertParity(TracingSettings.of(
                true, 1.0, Map.of(),
                true, false, true, true, TracingLogLevel.INFO
        ));
    }

    @Test
    void matchesAgentDigestForPolicyWithRouteRatios() {
        assertParity(TracingSettings.of(
                true, 0.25, Map.of("/checkout", 0.5, "/search", 0.3),
                true, true, false, false, TracingLogLevel.DEBUG
        ));
    }

    @Test
    void matchesAgentDigestForAllLogLevels() {
        for (TracingLogLevel level : TracingLogLevel.values()) {
            assertParity(TracingSettings.of(
                    false, 0.0, Map.of(),
                    false, false, false, false, level
            ));
        }
    }

    @Test
    void matchesAgentDigestWhenNegativeZeroIsCanonicalized() {
        assertParity(TracingSettings.of(
                        true, -0.0d, Map.of("/z", -0.0d),
                        true, false, true, true, TracingLogLevel.INFO
                ));
    }

    private static void assertParity(TracingSettings agentPolicy) {
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

        String springDigest = JmxTracingSettingsDigest.sha256(
                springPolicy.samplingEnabled(),
                springPolicy.samplingRatio(),
                springPolicy.samplingRouteRatios(),
                springPolicy.validationEnabled(),
                springPolicy.validationStrict(),
                springPolicy.exporterEnabled(),
                springPolicy.traceControlHeadersEnabled(),
                springPolicy.diagnosticsLogLevel());

        assertThat(springDigest)
                .as("Generated Spring digest must byte-for-byte match Agent TracingSettingsDigest")
                .isEqualTo(agentDigest);
    }

}
