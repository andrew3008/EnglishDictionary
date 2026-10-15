package space.br1440.platform.tracing.otel.javaagent.jmx.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.SpanTimeoutProcessor;

class ProcessorMetricsObservationSourceTest {

    private static final AgentInstanceId AGENT_INSTANCE_ID = AgentInstanceId.random();

    @Test
    void allThreeOwnersAbsentEncodePresenceFalse() {
        // given
        ProcessorMetricsObservationSource source = new ProcessorMetricsObservationSource(
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                AGENT_INSTANCE_ID);

        // when
        CompositeData observation = source.getProcessorMetricsObservation();
        CompositeData spanTimeout = (CompositeData) observation.get(
                JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_SPAN_TIMEOUT
        );
        CompositeData composite = (CompositeData) observation.get(
                JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_COMPOSITE
        );
        CompositeData metrics = (CompositeData) observation.get(
                JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_METRICS
        );

        // then
        assertThat((Boolean) spanTimeout.get(
                JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_OWNER_PRESENT)).isFalse();
        assertThat((Boolean) composite.get(
                JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_OWNER_PRESENT)).isFalse();
        assertThat((Boolean) metrics.get(
                JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_OWNER_PRESENT)).isFalse();
        assertThat((Long) spanTimeout.get(
                JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Long) composite.get(
                JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Long) metrics.get(
                JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((String) spanTimeout.get(
                JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_SCANNER_STATE)).isEmpty();
    }

    @Test
    void presentSpanTimeoutOwnerExportsLiveCounters() {
        // given
        SpanTimeoutProcessor spanTimeout = SpanTimeoutProcessor.start(
                Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), 10_000, 1_024, 60_000);
        OwnerRegistrationSlot<SpanTimeoutProcessor> slot = new OwnerRegistrationSlot<>();
        slot.replace(spanTimeout);
        ProcessorMetricsObservationSource source = new ProcessorMetricsObservationSource(
                slot,
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                AGENT_INSTANCE_ID);

        // when
        CompositeData observation = source.getProcessorMetricsObservation();
        CompositeData spanTimeoutValues = (CompositeData) observation.get(
                JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_SPAN_TIMEOUT);

        // then
        assertThat((Boolean) spanTimeoutValues.get(
                JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_OWNER_PRESENT)).isTrue();
        assertThat((Long) spanTimeoutValues.get(
                JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_FORCED_SPAN_END_ATTEMPTS_TOTAL)).isZero();
        assertThat((Integer) spanTimeoutValues.get(
                JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_ACTIVE_SPAN_COUNT)).isZero();
        spanTimeout.shutdown();
    }
}
