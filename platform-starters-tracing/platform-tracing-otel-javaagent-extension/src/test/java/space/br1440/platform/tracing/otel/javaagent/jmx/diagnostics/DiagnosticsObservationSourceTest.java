package space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

class DiagnosticsObservationSourceTest {

    private static final AgentInstanceId AGENT_INSTANCE_ID = AgentInstanceId.random();

    @Test
    void observationEncodesCompleteDiagnosticsSnapshot() {
        // given
        LongAdder invalidConfigCounter = new LongAdder();
        invalidConfigCounter.add(3L);
        DiagnosticsObservationSource source = new DiagnosticsObservationSource(invalidConfigCounter, AGENT_INSTANCE_ID);

        Map<String, Long> expectedSafeWrapperMetrics = TracingDiagnostics.shared().snapshot();
        // when
        CompositeData observation = source.getDiagnosticsObservation();

        // then
        assertThat(observation.getCompositeType().getTypeName())
                .isEqualTo(JmxWireContract.OPEN_TYPE_DIAGNOSTICS_OBSERVATION_TYPE_NAME);
        assertThat((String) observation.get(
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_AGENT_INSTANCE_ID))
                .isEqualTo(AGENT_INSTANCE_ID.toString());
        assertThat((Long) observation.get(
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_INVALID_CONFIG_COUNT))
                .isEqualTo(3L);
        assertThat(stringLongMap(observation)).isEqualTo(expectedSafeWrapperMetrics);
    }

    @Test
    void getterReturnsPublishedSnapshotUntilExplicitPublication() {
        // given
        DiagnosticsObservationSource source = new DiagnosticsObservationSource(new LongAdder(), AGENT_INSTANCE_ID);

        // when
        CompositeData first = source.getDiagnosticsObservation();
        CompositeData repeated = source.getDiagnosticsObservation();
        source.publishObservationSnapshot();
        CompositeData refreshed = source.getDiagnosticsObservation();

        // then
        assertThat(repeated).isSameAs(first);
        assertThat(refreshed).isNotSameAs(first);
    }

    @Test
    void constructorRejectsMissingDependencies() {
        // given/when/then
        assertThatThrownBy(() -> new DiagnosticsObservationSource(null, AGENT_INSTANCE_ID))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new DiagnosticsObservationSource(new LongAdder(), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static Map<String, Long> stringLongMap(CompositeData observation) {
        TabularData table = (TabularData) observation.get(
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_SAFE_WRAPPER_METRICS
        );

        Map<String, Long> result = new LinkedHashMap<>(table.size());
        for (Object rowObject : table.values()) {
            CompositeData row = (CompositeData) rowObject;
            result.put((String) row.get("key"), (Long) row.get("value"));
        }

        return result;
    }
}
