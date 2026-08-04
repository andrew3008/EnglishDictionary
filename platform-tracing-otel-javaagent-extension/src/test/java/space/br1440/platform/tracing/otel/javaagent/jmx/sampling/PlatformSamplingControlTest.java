package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformSamplingControlTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    @Test
    void samplingRatioAndEnabledReflectAcceptedPolicy() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(true, List.of(), List.of(), Map.of(), 0.42d);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        CompositeData observation = control.getSamplingObservation();

        assertThat((Double) observation.get(JmxObservationWireContract.SAMPLING_ITEM_SAMPLING_RATIO)).isEqualTo(0.42d);
        assertThat((Boolean) observation.get(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_ENABLED)).isTrue();
    }

    @Test
    void samplerEnabledReflectsAcceptedPolicyToggle() {
        AcceptedPolicyAccess enabled = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5d);
        AcceptedPolicyAccess disabled = AcceptedPolicyAccess.isolatedSampling(
                false, List.of(), List.of(), Map.of(), 0.5d);

        assertThat((Boolean) new PlatformSamplingControl(enabled, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID)
                .getSamplingObservation().get(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_ENABLED)).isTrue();
        assertThat((Boolean) new PlatformSamplingControl(disabled, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID)
                .getSamplingObservation().get(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_ENABLED)).isFalse();
    }

    @Test
    void routeRatiosReflectAcceptedPolicy() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of("/api", 0.2d), 0.5d);
        PlatformSamplingControl control = new PlatformSamplingControl(
                holder, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        TabularData routeRatios = (TabularData) control.getSamplingObservation()
                .get(JmxObservationWireContract.SAMPLING_ITEM_ROUTE_RATIOS);
        CompositeData row = routeRatios.get(new Object[]{"/api"});

        assertThat((Double) row.get("value")).isEqualTo(0.2d);
    }

    @Test
    void decisionCountsAreEmptyAndOwnerAbsentWhenCompositeSamplerOwnerAbsent() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5d);
        PlatformSamplingControl control = new PlatformSamplingControl(
                holder, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        CompositeData observation = control.getSamplingObservation();
        TabularData decisionCounts = (TabularData) observation
                .get(JmxObservationWireContract.SAMPLING_ITEM_DECISION_COUNTS);

        assertThat(decisionCounts.size()).isZero();
        assertThat((Boolean) observation.get(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_PRESENT)).isFalse();
    }

    @Test
    void decisionCountsReflectCompositeSamplerOwnerWhenPresent() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5d);
        OwnerRegistrationSlot<CompositeSampler> slot = new OwnerRegistrationSlot<>();
        CompositeSampler sampler = new CompositeSampler(holder);
        slot.replace(sampler);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, slot, AGENT_INSTANCE_ID);

        CompositeData observation = control.getSamplingObservation();
        TabularData decisionCounts = (TabularData) observation
                .get(JmxObservationWireContract.SAMPLING_ITEM_DECISION_COUNTS);

        // Владелец подключён — decode должен пройти без ContractViolation; конкретных решений
        // без реальных span'ов не будет, но карта обязана быть валидной TabularData того же типа.
        assertThat(decisionCounts.getTabularType().getTypeName())
                .isEqualTo("space.br1440.platform.tracing.SamplerDecisionCounts");
        assertThat((Boolean) observation.get(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_PRESENT)).isTrue();
        assertThat((Long) observation.get(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_GENERATION)).isPositive();
    }

    @Test
    void configLastUpdatedSourceIsAcceptedState() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5d);
        PlatformSamplingControl control = new PlatformSamplingControl(
                holder, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        assertThat((String) control.getSamplingObservation()
                .get(JmxObservationWireContract.SAMPLING_ITEM_CONFIG_LAST_UPDATED_SOURCE))
                .isEqualTo("accepted-state");
    }

    @Test
    void nullAcceptedPolicyIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new PlatformSamplingControl(null, new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID))
                .isInstanceOf(NullPointerException.class);
    }
}
