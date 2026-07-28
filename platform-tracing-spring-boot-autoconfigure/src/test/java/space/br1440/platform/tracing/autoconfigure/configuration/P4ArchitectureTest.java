package space.br1440.platform.tracing.autoconfigure.configuration;

import org.junit.jupiter.api.Test;

class P4ArchitectureTest {

    @Test
    void authorityEnumHasExactlyThreeValues() {
        TracingConfigurationAuthority[] values = TracingConfigurationAuthority.values();
        assert values.length == 3;
        assert values[0] == TracingConfigurationAuthority.HELM;
        assert values[1] == TracingConfigurationAuthority.CONFIG_SERVER;
        assert values[2] == TracingConfigurationAuthority.ACTUATOR;
    }

    @Test
    void postureEnumHasExactlyThreeValues() {
        PlatformEnvironmentPosture[] values = PlatformEnvironmentPosture.values();
        assert values.length == 3;
    }

    @Test
    void axesAreOrthogonal() {
        for (TracingConfigurationAuthority a : TracingConfigurationAuthority.values()) {
            assert !a.name().equals("LOCAL") && !a.name().equals("SHARED") && !a.name().equals("PRODUCTION");
        }
        for (PlatformEnvironmentPosture p : PlatformEnvironmentPosture.values()) {
            assert !p.name().equals("HELM") && !p.name().equals("CONFIG_SERVER") && !p.name().equals("ACTUATOR");
        }
    }

    @Test
    void guardRejectsActuatorWithSharedPosture() {
        try {
            new TracingConfigurationAuthorityGuard(
                    TracingConfigurationAuthority.ACTUATOR, PlatformEnvironmentPosture.SHARED, true)
                    .validate();
            throw new AssertionError("Expected exception");
        } catch (TracingConfigurationGuardException e) { /* expected */ }
    }

    @Test
    void guardRejectsActuatorWithProductionPosture() {
        try {
            new TracingConfigurationAuthorityGuard(
                    TracingConfigurationAuthority.ACTUATOR, PlatformEnvironmentPosture.PRODUCTION, true)
                    .validate();
            throw new AssertionError("Expected exception");
        } catch (TracingConfigurationGuardException e) { /* expected */ }
    }

    @Test
    void guardAllowsActuatorLocalWithoutExtraFlag() {
        TracingConfigurationAuthorityGuard guard = new TracingConfigurationAuthorityGuard(
                TracingConfigurationAuthority.ACTUATOR, PlatformEnvironmentPosture.LOCAL, true);
        guard.validate();
        assert guard.isActuatorMutationSurfaceAllowed();
 