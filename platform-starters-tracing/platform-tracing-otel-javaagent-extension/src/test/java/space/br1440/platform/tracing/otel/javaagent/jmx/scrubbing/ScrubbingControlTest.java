package space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntimeSupport;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link ScrubbingControl}: владелец наблюдения — {@link ScrubbingRuntime},
 * поэтому деградация сессии видна в JMX независимо от того, зарегистрирован ли
 * processor-адаптер, а ключевая проверка — presence-дискриминатор вместо прежнего
 * sentinel {@code -1L}.
 */
class ScrubbingControlTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    private static ScrubbingRuntime enabledRuntime() {
        return ScrubbingRuntimeSupport.fromBuiltInRules(
                List.of(BuiltInSpanAttributeScrubbingRules.resolve("email")),
                MaskedSecret.of("k"),
                false);
    }

    @Test
    void ownerAbsentGivesPlaceholderValuesNotSentinel() {
        ScrubbingControl control = new ScrubbingControl(new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        CompositeData observation = control.getScrubbingObservation();

        assertThat((Boolean) observation.get(JmxObservationWireContract.ITEM_OWNER_PRESENT)).isFalse();
        assertThat((Long) observation.get(JmxObservationWireContract.ITEM_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Boolean) observation.get(JmxObservationWireContract.SCRUBBING_ITEM_ENABLED)).isFalse();
        assertThat((Long) observation.get(JmxObservationWireContract.SCRUBBING_ITEM_POLICY_VERSION)).isEqualTo(0L);
        TabularData metrics = (TabularData) observation.get(JmxObservationWireContract.SCRUBBING_ITEM_METRICS);
        assertThat(metrics.size()).isZero();
    }

    @Test
    void ownerPresentReflectsRuntimeStateAndPositiveGeneration() {
        OwnerRegistrationSlot<ScrubbingRuntime> slot = new OwnerRegistrationSlot<>();
        slot.replace(enabledRuntime());
        ScrubbingControl control = new ScrubbingControl(slot, AGENT_INSTANCE_ID);

        CompositeData observation = control.getScrubbingObservation();

        assertThat((Boolean) observation.get(JmxObservationWireContract.ITEM_OWNER_PRESENT)).isTrue();
        assertThat((Long) observation.get(JmxObservationWireContract.ITEM_OWNER_GENERATION)).isPositive();
        assertThat((Boolean) observation.get(JmxObservationWireContract.SCRUBBING_ITEM_ENABLED)).isTrue();
    }

    @Test
    void collapseModeIsVisibleInLiveMetricsWithoutRevokingInstallation() {
        OwnerRegistrationSlot<ScrubbingRuntime> slot = new OwnerRegistrationSlot<>();
        ScrubbingRuntime runtime = enabledRuntime();
        runtime.markAttributesUnverified();
        slot.replace(runtime);
        ScrubbingControl control = new ScrubbingControl(slot, AGENT_INSTANCE_ID);

        TabularData metrics = (TabularData) control.getScrubbingObservation()
                .get(JmxObservationWireContract.SCRUBBING_ITEM_METRICS);

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
        OwnerRegistrationSlot<ScrubbingRuntime> slot = new OwnerRegistrationSlot<>();
        ScrubbingRuntime runtime = enabledRuntime();
        for (int i = 0; i < 5; i++) {
            runtime.markAttributesUnverified();
        }
        runtime.recordCollapsedSpan();
        runtime.recordCollapsedSpan();
        slot.replace(runtime);
        ScrubbingControl control = new ScrubbingControl(slot, AGENT_INSTANCE_ID);

        TabularData metrics = (TabularData) control.getScrubbingObservation()
                .get(JmxObservationWireContract.SCRUBBING_ITEM_METRICS);

        assertThat((Long) metrics.get(new Object[]{"collapse_mode_activations"}).get("value")).isEqualTo(1L);
        assertThat((Long) metrics.get(new Object[]{"collapsed_spans"}).get("value")).isEqualTo(2L);
    }

    @Test
    void isolatedMBeanServerLifecycleDoesNotLeakOwnerOntoThePlatformServer() throws Exception {
        javax.management.MBeanServer isolated = javax.management.MBeanServerFactory.createMBeanServer();
        javax.management.MBeanServer platform = java.lang.management.ManagementFactory.getPlatformMBeanServer();
        boolean platformHadScrubbing = platform.isRegistered(
                space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames.SCRUBBING);
        try {
            OwnerRegistrationSlot<ScrubbingRuntime> slot = new OwnerRegistrationSlot<>();
            slot.replace(enabledRuntime());
            ScrubbingControl control = new ScrubbingControl(slot, AGENT_INSTANCE_ID);
            isolated.registerMBean(control,
                    space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames.SCRUBBING);

            assertThat(isolated.isRegistered(
                    space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames.SCRUBBING)).isTrue();
            CompositeData observation = (CompositeData) isolated.getAttribute(
                    space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames.SCRUBBING,
                    "ScrubbingObservation");
            assertThat((Boolean) observation.get(JmxObservationWireContract.ITEM_OWNER_PRESENT)).isTrue();
        } finally {
            javax.management.MBeanServerFactory.releaseMBeanServer(isolated);
        }

        assertThat(platform.isRegistered(
                space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames.SCRUBBING))
                .isEqualTo(platformHadScrubbing);
    }
}
