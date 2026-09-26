package space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

class ScrubbingObservationSourceTest {

    private static final AgentInstanceId AGENT_INSTANCE_ID = AgentInstanceId.random();

    private static ScrubbingRuntime enabledRuntime() {
        return ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                List.of(BuiltInSpanAttributeScrubbingRules.EMAIL.create()),
                MaskedSecret.of("k"),
                false);
    }

    @Test
    void ownerAbsentGivesPlaceholderValuesNotSentinel() {
        // given
        ScrubbingObservationSource source = new ScrubbingObservationSource(
                new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        // when
        CompositeData observation = source.getScrubbingObservation();

        // then
        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_PRESENT)).isFalse();
        assertThat((Long) observation.get(
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_SCRUBBING_ENABLED)).isFalse();
        assertThat((Long) observation.get(
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_POLICY_VERSION)).isEqualTo(0L);
        TabularData metrics = (TabularData) observation.get(
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_SCRUBBING_METRICS);
        assertThat(metrics.size()).isZero();
    }

    @Test
    void ownerPresentReflectsRuntimeStateAndPositiveGeneration() {
        // given
        OwnerRegistrationSlot<ScrubbingRuntime> slot = new OwnerRegistrationSlot<>();
        slot.replace(enabledRuntime());
        ScrubbingObservationSource source = new ScrubbingObservationSource(slot, AGENT_INSTANCE_ID);

        // when
        CompositeData observation = source.getScrubbingObservation();

        // then
        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_PRESENT)).isTrue();
        assertThat((Long) observation.get(
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_OWNER_GENERATION)).isPositive();
        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_SCRUBBING_OBSERVATION_SCRUBBING_ENABLED)).isTrue();
    }

    @Test
    void collapseModeIsVisibleInLiveMetricsWithoutRevokingInstallation() {
        // given
        OwnerRegistrationSlot<ScrubbingRuntime> slot = new OwnerRegistrationSlot<>();
        ScrubbingRuntime runtime = enabledRuntime();
        runtime.markAttributesUnverified();
        slot.replace(runtime);
        ScrubbingObservationSource source = new ScrubbingObservationSource(slot, AGENT_INSTANCE_ID);

        // when
        TabularData metrics = (TabularData) source.getScrubbingObservation()
                .get(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_SCRUBBING_METRICS);

        // then
        assertThat((Long) metrics.get(new Object[]{"attributes_verified"}).get("value")).isEqualTo(0L);
        assertThat((Long) metrics.get(new Object[]{"runtime_state"}).get("value"))
                .as("1 = DEGRADED_FAIL_CLOSED")
                .isEqualTo(1L);
        assertThat((Long) metrics.get(new Object[]{"collapse_mode_activations"}).get("value"))
                .as("активация режима считается один раз за сессию, а не по числу span'ов")
                .isEqualTo(1L);
        assertThat((Long) metrics.get(new Object[]{"collapsed_spans"}).get("value")).isZero();
    }

    @Test
    void repeatedDegradationDoesNotInflateTheActivationCounter() {
        // given
        OwnerRegistrationSlot<ScrubbingRuntime> slot = new OwnerRegistrationSlot<>();
        ScrubbingRuntime runtime = enabledRuntime();
        for (int i = 0; i < 5; i++) {
            runtime.markAttributesUnverified();
        }
        runtime.recordCollapsedSpan();
        runtime.recordCollapsedSpan();
        slot.replace(runtime);
        ScrubbingObservationSource source = new ScrubbingObservationSource(slot, AGENT_INSTANCE_ID);

        // when
        TabularData metrics = (TabularData) source.getScrubbingObservation()
                .get(JmxWireContract.FIELD_SCRUBBING_OBSERVATION_SCRUBBING_METRICS);

        // then
        assertThat((Long) metrics.get(new Object[]{"collapse_mode_activations"}).get("value")).isEqualTo(1L);
        assertThat((Long) metrics.get(new Object[]{"collapsed_spans"}).get("value")).isEqualTo(2L);
    }

}
