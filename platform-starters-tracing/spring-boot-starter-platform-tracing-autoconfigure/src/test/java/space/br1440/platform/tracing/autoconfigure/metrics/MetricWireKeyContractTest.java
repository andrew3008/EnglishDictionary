package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class MetricWireKeyContractTest {

    @Test
    void samplerDecisionKeysMatchTheSupportedWireKeySpace() {
        // when/then
        assertThat(Arrays.stream(SamplerDecisionKey.values()).map(SamplerDecisionKey::wireKey).toList())
                .containsExactlyInAnyOrder(
                        "RECORD_AND_SAMPLE:force_header",
                        "RECORD_AND_SAMPLE:qa_trace",
                        "RECORD_AND_SAMPLE:parent_sampled",
                        "RECORD_AND_SAMPLE:route_ratio",
                        "RECORD_AND_SAMPLE:global_ratio",
                        "DROP:kill_switch",
                        "DROP:drop_path",
                        "DROP:parent_drop",
                        "DROP:route_ratio_drop",
                        "DROP:global_ratio_drop",
                        "DROP:fallback_drop");
    }

}
