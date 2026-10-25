package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

class SamplingObservationSourceTest {

    private static final AgentInstanceId AGENT_INSTANCE_ID = AgentInstanceId.random();

    @Test
    void samplingRatioAndEnabledReflectAcceptedPolicy() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.42d);
        SamplingObservationSource source = new SamplingObservationSource(
                holder, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        // when
        CompositeData observation = source.getSamplingObservation();

        // then
        assertThat((Double) observation.get(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_RATIO)).isEqualTo(0.42d);
        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_ENABLED)).isTrue();
    }

    @Test
    void samplerEnabledReflectsAcceptedPolicyToggle() {
        // given
        TracingSettingsSource enabled = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.5d);
        TracingSettingsSource disabled = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), false, Map.of(), 0.5d);

        // when/then
        assertThat((Boolean) new SamplingObservationSource(enabled, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID)
                .getSamplingObservation()
                .get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_ENABLED)).isTrue();
        assertThat((Boolean) new SamplingObservationSource(disabled, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID)
                .getSamplingObservation()
                .get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_ENABLED)).isFalse();
    }

    @Test
    void routeRatioCountReflectsAcceptedPolicy() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(),
                true, Map.of("/api", 0.2d), 0.5d);
        SamplingObservationSource source = new SamplingObservationSource(
                holder, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        // when
        long routeRatioCount = (Long) source.getSamplingObservation()
                .get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_ROUTE_RATIO_COUNT);

        // then
        assertThat(routeRatioCount).isEqualTo(1L);
    }

    @Test
    void decisionCountsAreEmptyAndOwnerAbsentWhenCompositeSamplerOwnerAbsent() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.5d);
        SamplingObservationSource source = new SamplingObservationSource(
                holder, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        // when
        CompositeData observation = source.getSamplingObservation();
        TabularData decisionCounts = (TabularData) observation
                .get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_DECISION_COUNTS);

        // then
        assertThat(decisionCounts.size()).isZero();
        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_PRESENT)).isFalse();
    }

    @Test
    void decisionCountsReflectCompositeSamplerOwnerWhenPresent() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.5d);
        OwnerRegistrationSlot<CompositeSampler> slot = new OwnerRegistrationSlot<>();
        CompositeSampler sampler = new CompositeSampler(holder, CategoryBSamplingAccess.snapshot(List.of(), List.of()));
        slot.replace(sampler);
        SamplingObservationSource source = new SamplingObservationSource(holder, slot, AGENT_INSTANCE_ID);

        // when
        CompositeData observation = source.getSamplingObservation();
        TabularData decisionCounts = (TabularData) observation
                .get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_DECISION_COUNTS);

        // then
        assertThat(decisionCounts.getTabularType().getTypeName())
                .isEqualTo("space.br1440.platform.tracing.SamplerDecisionCounts");
        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_PRESENT)).isTrue();
        assertThat((Long) observation.get(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_GENERATION)).isPositive();
    }

    @Test
    void configLastUpdatedSourceReflectsAcceptedPolicyAuthority() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.5d);
        SamplingObservationSource source = new SamplingObservationSource(
                holder, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        // when/then
        assertThat((String) source.getSamplingObservation()
                .get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_CONFIG_LAST_UPDATED_SOURCE))
                .isEqualTo(SettingsSourceAuthority.BOOTSTRAP.name());
    }

    @Test
    void nullAcceptedPolicyIsRejectedAtConstruction() {
        // given/when/then
        assertThatThrownBy(() -> new SamplingObservationSource(null, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID))
                .isInstanceOf(NullPointerException.class);
    }
}
