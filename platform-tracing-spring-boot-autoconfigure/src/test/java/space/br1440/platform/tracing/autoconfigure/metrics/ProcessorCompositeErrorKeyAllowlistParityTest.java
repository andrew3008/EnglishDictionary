package space.br1440.platform.tracing.autoconfigure.metrics;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessorCompositeErrorKeyAllowlistParityTest {

    @Test
    void namedConstantsAreExactMirrorOfAllowlist() {
        Set<String> expectedNames = new LinkedHashSet<>(JmxObservationWireContract.PROCESSOR_ERRORS_BY_NAME_ALLOWLIST);

        Set<String> actualNames = new LinkedHashSet<>();
        for (ProcessorCompositeErrorKey key : ProcessorCompositeErrorKey.values()) {
            if (key == ProcessorCompositeErrorKey.TOTAL) {
                assertThat(key.processorName()).isNull();
                continue;
            }
            actualNames.add(key.processorName());
        }

        assertThat(expectedNames).hasSize(6);
        assertThat(actualNames).isEqualTo(expectedNames);
    }
}
