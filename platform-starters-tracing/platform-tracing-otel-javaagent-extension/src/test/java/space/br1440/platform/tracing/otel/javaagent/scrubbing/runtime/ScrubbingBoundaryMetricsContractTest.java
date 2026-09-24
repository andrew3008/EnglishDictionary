package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class ScrubbingBoundaryMetricsContractTest {

    @Test
    void boundary_metrics_exposes_the_pinned_read_only_projection() {
        // given
        RecordComponent[] components = ScrubbingBoundaryMetrics.class.getRecordComponents();

        // when
        String[] names = Arrays.stream(components).map(RecordComponent::getName).toArray(String[]::new);

        // then
        assertThat(names).containsExactly(
                "inputSpans",
                "outputSpans",
                "spansChanged",
                "spansDroppedFailClosed",
                "eventsScrubbed",
                "linksScrubbed",
                "spanNamesScrubbed",
                "exceptionEventsCanonicalized",
                "collapseFailures",
                "exporterInvocations",
                "hashDowngradedToMask",
                "truncateDowngradedToMask",
                "criticalFailures",
                "nonCriticalFailures",
                "skippedCircuitOpen",
                "surfacesCollapsedFailClosed",
                "batchPreparationFailures",
                "valueAttributesRemoved");
        assertThat(components).allSatisfy(component -> assertThat(component.getType()).isEqualTo(long.class));
    }
}
