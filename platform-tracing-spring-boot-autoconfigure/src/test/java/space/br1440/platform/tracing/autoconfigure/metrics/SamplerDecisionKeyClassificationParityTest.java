package space.br1440.platform.tracing.autoconfigure.metrics;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract.DynamicMapKey;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract.KeyCardinality;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract.ValueSemantics;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SamplerDecisionKeyClassificationParityTest {

    @Test
    void samplerDecisionKeyIsExactMirrorOfSamplingDecisionCountsClassification() {
        Set<String> classificationKeys = JmxObservationWireContract.SAMPLING_DECISION_COUNTS_CLASSIFICATION.stream()
                .map(DynamicMapKey::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> enumKeys = new LinkedHashSet<>();
        for (SamplerDecisionKey key : SamplerDecisionKey.values()) {
            enumKeys.add(key.wireKey());
        }

        assertThat(classificationKeys).hasSize(11);
        assertThat(enumKeys).isEqualTo(classificationKeys);
    }

    @Test
    void everyClassificationEntryIsFixedEnumCumulative() {
        for (DynamicMapKey entry : JmxObservationWireContract.SAMPLING_DECISION_COUNTS_CLASSIFICATION) {
            assertThat(entry.cardinality()).as("cardinality of %s", entry.key()).isEqualTo(KeyCardinality.FIXED_ENUM);
            assertThat(entry.semantics()).as("semantics of %s", entry.key()).isEqualTo(ValueSemantics.CUMULATIVE);
        }
    }
}
