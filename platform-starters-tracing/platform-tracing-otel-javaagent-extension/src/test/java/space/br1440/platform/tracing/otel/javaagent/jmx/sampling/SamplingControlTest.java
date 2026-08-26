package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

class SamplingControlTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    @Test
    void samplingRatioAndEnabledReflectAcceptedPolicy() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.42d);
        SamplingControl control = new SamplingControl(holder, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        CompositeData observation = control.getSamplingObservation();

        assertThat((Double) observation.get(
                GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_RATIO)).isEqualTo(0.42d);
        assertThat((Boolean) observation.get(
                GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_ENABLED)).isTrue();
    }

    @Test
    void samplerEnabledReflectsAcceptedPolicyToggle() {
        TracingSettingsSource enabled = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5d);
        TracingSettingsSource disabled = TracingSettingsSource.isolatedSampling(false, Map.of(), 0.5d);

        assertThat((Boolean) new SamplingControl(enabled, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID)
                .getSamplingObservation()
                .get(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_ENABLED)).isTrue();
        assertThat((Boolean) new SamplingControl(disabled, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID)
                .getSamplingObservation()
                .get(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_ENABLED)).isFalse();
    }

    @Test
    void routeRatiosReflectAcceptedPolicy() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(
                true, Map.of("/api", 0.2d), 0.5d);
        SamplingControl control = new SamplingControl(
                holder, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        TabularData routeRatios = (TabularData) control.getSamplingObservation()
                .get(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_ROUTE_RATIOS);
        CompositeData row = routeRatios.get(new Object[]{"/api"});

        assertThat((Double) row.get("value")).isEqualTo(0.2d);
    }

    @Test
    void decisionCountsAreEmptyAndOwnerAbsentWhenCompositeSamplerOwnerAbsent() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5d);
        SamplingControl control = new SamplingControl(
                holder, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        CompositeData observation = control.getSamplingObservation();
        TabularData decisionCounts = (TabularData) observation
                .get(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_DECISION_COUNTS);

        assertThat(decisionCounts.size()).isZero();
        assertThat((Boolean) observation.get(
                GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_PRESENT)).isFalse();
    }

    @Test
    void decisionCountsReflectCompositeSamplerOwnerWhenPresent() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5d);
        OwnerRegistrationSlot<CompositeSampler> slot = new OwnerRegistrationSlot<>();
        CompositeSampler sampler = new CompositeSampler(holder, CategoryBSamplingAccess.snapshot(List.of(), List.of()));
        slot.replace(sampler);
        SamplingControl control = new SamplingControl(holder, slot, AGENT_INSTANCE_ID);

        CompositeData observation = control.getSamplingObservation();
        TabularData decisionCounts = (TabularData) observation
                .get(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_DECISION_COUNTS);

        // Владелец подключён — decode должен пройти без ContractViolation; конкретных решений
        // без реальных span'ов не будет, но карта обязана быть валидной TabularData того же типа.
        assertThat(decisionCounts.getTabularType().getTypeName())
                .isEqualTo("space.br1440.platform.tracing.SamplerDecisionCounts");
        assertThat((Boolean) observation.get(
                GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_PRESENT)).isTrue();
        assertThat((Long) observation.get(
                GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_GENERATION)).isPositive();
    }

    @Test
    void configLastUpdatedSourceIsAcceptedState() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5d);
        SamplingControl control = new SamplingControl(
                holder, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        assertThat((String) control.getSamplingObservation()
                .get(GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_CONFIG_LAST_UPDATED_SOURCE))
                .isEqualTo("accepted-state");
    }

    @Test
    void nullAcceptedPolicyIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new SamplingControl(null, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID))
                .isInstanceOf(NullPointerException.class);
    }
}
