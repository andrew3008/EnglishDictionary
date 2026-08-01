package space.br1440.platform.tracing.autoconfigure.control.policy;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.bootstrap.PlatformEnvironmentPosture;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsResolver;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority;
import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationAuthorityGuard;
import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationGuardException;

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
            assert !a.name().equals("LOCAL") && !a.name().equals("SHARED")
                    && !a.name().equals("PRODUCTION") : "Authority polluted with posture name: " + a;
        }
        for (PlatformEnvironmentPosture p : PlatformEnvironmentPosture.values()) {
            assert !p.name().equals("HELM") && !p.name().equals("CONFIG_SERVER")
                    && !p.name().equals("ACTUATOR") : "Posture polluted with authority name: " + p;
        }
    }

    @Test
    void guardRejectsActuatorWithSharedPosture() {
        try {
            new TracingConfigurationAuthorityGuard(
                    TracingConfigurationAuthority.ACTUATOR,
                    PlatformEnvironmentPosture.SHARED, true).validate();
            throw new AssertionError("Expected TracingConfigurationGuardException");
        } catch (TracingConfigurationGuardException e) { /* expected */ }
    }

    @Test
    void guardRejectsActuatorWithProductionPosture() {
        try {
            new TracingConfigurationAuthorityGuard(
                    TracingConfigurationAuthority.ACTUATOR,
                    PlatformEnvironmentPosture.PRODUCTION, true).validate();
            throw new AssertionError("Expected TracingConfigurationGuardException");
        } catch (TracingConfigurationGuardException e) { /* expected */ }
    }

    @Test
    void guardAllowsActuatorLocalWithoutExtraFlag() {
        TracingConfigurationAuthorityGuard guard = new TracingConfigurationAuthorityGuard(
                TracingConfigurationAuthority.ACTUATOR, PlatformEnvironmentPosture.LOCAL, true);
        guard.validate();
        assert guard.isActuatorMutationSurfaceAllowed();
    }

    @Test
    void guardRejectsMissingAuthority() {
        try {
            new TracingBootstrapSettingsResolver().resolve(
                    new org.springframework.mock.env.MockEnvironment()
                            .withProperty("platform.environment", "LOCAL"));
            throw new AssertionError("Expected fail-fast on missing authority");
        } catch (IllegalStateException e) { /* expected */ }
    }

    @Test
    void guardRejectsDisabledTracingWithConfigServer() {
        try {
            new TracingConfigurationAuthorityGuard(
                    new TracingBootstrapSettingsResolver().resolve(
                            new org.springframework.mock.env.MockEnvironment()
                            .withProperty("platform.tracing.configuration.authority", "CONFIG_SERVER")
                            .withProperty("platform.environment", "LOCAL")
                            .withProperty("platform.tracing.enabled", "false")))
                    .validate();
            throw new AssertionError("Expected fail-fast on disabled tracing + CONFIG_SERVER");
        } catch (TracingConfigurationGuardException e) { /* expected */ }
    }

    @Test
    void guardRejectsUnknownPosture() {
        try {
            new TracingBootstrapSettingsResolver().resolve(
                    new org.springframework.mock.env.MockEnvironment()
                            .withProperty("platform.tracing.configuration.authority", "HELM")
                            .withProperty("platform.environment", "dev"));
            throw new AssertionError("Expected fail-fast on alias 'dev'");
        } catch (IllegalStateException e) { /* expected */ }
    }

    @Test
    void guardRejectsTypoPosture() {
        try {
            new TracingBootstrapSettingsResolver().resolve(
                    new org.springframework.mock.env.MockEnvironment()
                            .withProperty("platform.tracing.configuration.authority", "HELM")
                            .withProperty("platform.environment", "LOACL"));
            throw new AssertionError("Expected fail-fast on typo 'LOACL'");
        } catch (IllegalStateException e) { /* expected */ }
    }

    @Test
    void snapshotBuilderRejectsPartialSnapshot() {
        try {
            CompleteTracingPolicySnapshot.builder()
                    .samplingEnabled(true)
                    .samplingRatio(0.5)
                    // 6 leaves intentionally omitted
                    .build();
            throw new AssertionError("Expected TracingPolicyManifestException for partial snapshot");
        } catch (TracingPolicyManifestException e) { /* expected */ }
    }

    @Test
    void snapshotBuilderRequiresAllEightLeaves() {
        CompleteTracingPolicySnapshot s = CompleteTracingPolicySnapshot.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5)
                .samplingRouteRatios(java.util.Map.of())
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
        assert s.samplingEnabled();
        assert s.samplingRatio() == 0.5;
        assert s.samplingRouteRatios().isEmpty();
    }

    @Test
    void singleLeafMutationPreservesRouteRatiosWithoutSpringSideCache() {
        // P4 explicitly forbids a Spring-side accepted-state cache/second source of truth
        // (see change policy). There is intentionally no InProcessAcceptedSnapshotStore or
        // equivalent in production; the only accepted-state owner is the Agent, reached via
        // CompleteTracingPolicyReader.currentComplete(). This test proves the withX()
        // copy primitive itself preserves the other seven leaves — the actual read-copy-change
        // orchestration is covered by TracingPolicyMutationServiceTest against the real
        // adapter and its real CompleteTracingPolicyReader collaborator.
        CompleteTracingPolicySnapshot original = CompleteTracingPolicySnapshot.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(java.util.Map.of("checkout", 0.5, "search", 0.3))
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();

        CompleteTracingPolicySnapshot mutated = original.withExporterEnabled(false);

        assert !mutated.exporterEnabled();
        assert mutated.samplingRouteRatios().size() == 2 :
                "Route ratios must be preserved; got: " + mutated.samplingRouteRatios().size();
        assert mutated.samplingRouteRatios().get("checkout") == 0.5;
        assert mutated.samplingRouteRatios().get("search") == 0.3;
        assert mutated.samplingEnabled();
        assert mutated.validationEnabled();
        assert !mutated.validationStrict();
        assert mutated.traceControlHeadersEnabled();
        assert mutated.diagnosticsLogLevel().equals("INFO");
    }
}
