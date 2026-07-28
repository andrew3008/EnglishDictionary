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
    void guardRejectsUnknownPosture() {
        try {
            PlatformEnvironmentPostureResolver.resolve(
                    new org.springframework.mock.env.MockEnvironment()
                            .withProperty("platform.environment", "dev"));
            throw new AssertionError("Expected fail-fast on alias 'dev'");
        } catch (TracingConfigurationGuardException e) { /* expected */ }
    }

    @Test
    void guardRejectsTypoPosture() {
        try {
            PlatformEnvironmentPostureResolver.resolve(
                    new org.springframework.mock.env.MockEnvironment()
                            .withProperty("platform.environment", "LOACL"));
            throw new AssertionError("Expected fail-fast on typo 'LOACL'");
        } catch (TracingConfigurationGuardException e) { /* expected */ }
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
    void inProcessStorePreservesRouteRatiosAcrossSingleLeafMutation() {
        InProcessAcceptedSnapshotStore store = new InProcessAcceptedSnapshotStore();
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
        store.update(new AcceptedSnapshotWithSequence(10L, original));

        // Simulate single-leaf mutation
        AcceptedSnapshotWithSequence accepted = store.currentComplete().orElseThrow();
        CompleteTracingPolicySnapshot mutated = accepted.snapshot().withExporterEnabled(false);

        assert mutated.exporterEnabled() == false;
        assert mutated.samplingRouteRatios().size() == 2 :
                "Route ratios must be preserved; got: " + mutated.samplingRouteRatios().size();
        assert mutated.samplingRouteRatios().get("checkout") == 0.5;
        assert mutated.samplingRouteRatios().get("search") == 0.3;
        assert mutated.samplingEnabled();
    }
}
