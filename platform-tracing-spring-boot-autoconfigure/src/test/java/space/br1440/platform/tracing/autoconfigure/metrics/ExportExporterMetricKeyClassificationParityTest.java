package space.br1440.platform.tracing.autoconfigure.metrics;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract.DynamicMapKey;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract.ValueSemantics;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ExportExporterMetricKeyClassificationParityTest {

    @Test
    void enumIsExactMirrorOfCumulativeClassificationEntries() {
        Set<String> expectedCumulativeKeys = JmxObservationWireContract.EXPORT_EXPORTER_METRICS_CLASSIFICATION.stream()
                .filter(entry -> entry.semantics() == ValueSemantics.CUMULATIVE)
                .map(DynamicMapKey::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> enumKeys = new LinkedHashSet<>();
        for (ExportExporterMetricKey key : ExportExporterMetricKey.values()) {
            enumKeys.add(key.wireKey());
        }

        assertThat(expectedCumulativeKeys).hasSize(7);
        assertThat(enumKeys).isEqualTo(expectedCumulativeKeys);
    }
}
