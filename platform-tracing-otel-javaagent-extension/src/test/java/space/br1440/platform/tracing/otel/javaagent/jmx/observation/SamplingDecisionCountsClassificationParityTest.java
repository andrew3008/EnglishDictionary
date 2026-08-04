package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract.DynamicMapKey;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract.KeyCardinality;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract.ValueSemantics;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SamplingDecisionCountsClassificationParityTest {

    @Test
    void classificationIsExactlyElevenFixedEnumCumulativeKeysMatchingProducerReasonSpace() {
        Set<String> expectedKeys = new LinkedHashSet<>();
        for (String reasonCode : PlatformSamplingReasons.EXPORTED) {
            expectedKeys.add("RECORD_AND_SAMPLE:" + reasonCode);
        }

        for (String reasonCode : PlatformSamplingReasons.DROPPED) {
            expectedKeys.add("DROP:" + reasonCode);
        }

        expectedKeys.add("DROP:" + PlatformSamplingReasons.KILL_SWITCH);
        expectedKeys.add("DROP:" + PlatformSamplingReasons.FALLBACK_DROP);

        assertThat(expectedKeys).hasSize(11);

        Set<String> actualKeys = new LinkedHashSet<>();
        for (DynamicMapKey entry : JmxObservationWireContract.SAMPLING_DECISION_COUNTS_CLASSIFICATION) {
            actualKeys.add(entry.key());
            assertThat(entry.cardinality())
                    .as("cardinality of %s", entry.key())
                    .isEqualTo(KeyCardinality.FIXED_ENUM);
            assertThat(entry.semantics())
                    .as("semantics of %s", entry.key())
                    .isEqualTo(ValueSemantics.CUMULATIVE);
        }

        assertThat(actualKeys).isEqualTo(expectedKeys);
    }

    @Test
    void routeRatiosClassificationRemainsDynamicNotExported() {
        assertThat(JmxObservationWireContract.SAMPLING_ROUTE_RATIOS_CLASSIFICATION).hasSize(1);
        DynamicMapKey wildcard = JmxObservationWireContract.SAMPLING_ROUTE_RATIOS_CLASSIFICATION.get(0);
        assertThat(wildcard.cardinality()).isEqualTo(KeyCardinality.DYNAMIC_NOT_EXPORTED);
        assertThat(wildcard.semantics()).isEqualTo(ValueSemantics.POINT_IN_TIME);
    }
}
