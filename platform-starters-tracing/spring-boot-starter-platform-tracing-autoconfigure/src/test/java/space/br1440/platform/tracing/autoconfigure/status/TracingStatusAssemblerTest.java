package space.br1440.platform.tracing.autoconfigure.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceTestSupport;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.PlatformEnvironmentPosture;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedPolicySummary;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsReadModel;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorWatchdogValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.autoconfigure.support.ReadinessJmxTestFixtures;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

class TracingStatusAssemblerTest {

    @Test
    void reasonPriorityStartsWithDisabledThenAuthority() {
        TracingStatusAssembler disabled = assembler(
                false,
                TracingConfigurationAuthority.HELM,
                diagnostics(AgentRuntimeState.AGENT_MISSING)
        );

        TracingStatusAssembler helm = assembler(
                true,
                TracingConfigurationAuthority.HELM,
                diagnostics(AgentRuntimeState.AGENT_MISSING)
        );

        assertThat(disabled.assemble().settings().reason())
                .isEqualTo(SettingsAbsenceReason.TRACING_DISABLED);
        assertThat(helm.assemble().settings().reason())
                .isEqualTo(SettingsAbsenceReason.AUTHORITY_HAS_NO_ACCEPTED_SETTINGS);
    }

    @Test
    void writeAuthorityDistinguishesAgentUnavailableAndPolicyNotAccepted() {
        TracingStatusAssembler unavailable = assembler(
                true,
                TracingConfigurationAuthority.CONFIG_SERVER,
                diagnostics(AgentRuntimeState.EXTENSION_FAILED)
        );

        TracingStatusAssembler notAccepted = assembler(
                true,
                TracingConfigurationAuthority.CONFIG_SERVER,
                diagnostics(AgentRuntimeState.AGENT_READY)
        );

        assertThat(unavailable.assemble().settings().reason())
                .isEqualTo(SettingsAbsenceReason.AGENT_UNAVAILABLE);

        assertThat(notAccepted.assemble().settings().reason())
                .isEqualTo(SettingsAbsenceReason.SETTINGS_NOT_ACCEPTED);
    }

    @Test
    void acceptedPolicyIsSecretSafeAndPostStartAgentLossIsObserved() {
        AtomicReference<TracingDeploymentDiagnostics> state = new AtomicReference<>(diagnostics(AgentRuntimeState.AGENT_READY));

        AcceptedSettingsReadModel projection = new AcceptedSettingsReadModel(
                new ExpectedSettingsRevision("runtime-1", 7L),
                new AcceptedPolicySummary(true, 0.25, 3, true, false, true, true, "INFO")
        );

        TracingStatusAssembler assembler = assembler(
                true,
                TracingConfigurationAuthority.CONFIG_SERVER,
                state,
                projection
        );

        TracingStatusSnapshot initial = assembler.assemble();
        assertThat(initial.settings().available()).isTrue();
        assertThat(initial.settings().accepted().samplingRouteRatiosCount()).isEqualTo(3);
        assertThat(initial.agent())
                .containsEntry("contractId", "platform-tracing-jmx-wire-contract")
                .containsEntry("runtimeEpoch", "runtime-epoch-1")
                .containsEntry("evidenceGeneration", 1L)
                .containsEntry("batchId", "batch-1")
                .containsEntry("agentDistributionSha256", "a".repeat(64))
                .containsEntry("extensionDefiningLoader", "agent-loader@1")
                .containsEntry("sdkDefiningLoader", "sdk-loader@2");

        state.set(diagnostics(AgentRuntimeState.AGENT_MISSING));
        TracingStatusSnapshot degraded = assembler.assemble();
        assertThat(degraded.settings().available()).isFalse();
        assertThat(degraded.settings().reason()).isEqualTo(SettingsAbsenceReason.AGENT_UNAVAILABLE);
    }

    @Test
    void runtimeValidationSectionIncludesViolationCounter() {
        PlatformTracingJmxClient jmxClient = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
        ValidationValues values = new ValidationValues(true, true, true, 42L, 7L, "accepted-state");
        when(jmxClient.validation().observation()).thenReturn(Optional.of(
                new ValidationObservation("agent-instance", new JmxOwnerState.Present<>(values, 1L))));

        TracingBootstrapSettings settings = new TracingBootstrapSettings(
                true,
                TracingConfigurationAuthority.HELM,
                PlatformEnvironmentPosture.LOCAL);

        TracingStatusAssembler assembler = new TracingStatusAssembler(
                NoopTraceOperations.INSTANCE,
                NoOpTracingRuntime.noop(),
                settings,
                jmxClient,
                Optional::empty,
                diagnostics(AgentRuntimeState.AGENT_READY),
                stableCoherenceGuard());

        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) assembler.assemble().runtime().get("validation");
        assertThat(validation).containsEntry("violationsTotal", 42L);
    }

    @Test
    void runtimeWatchdogSectionExposesLiveAndBoundedState() {
        // given
        PlatformTracingJmxClient jmxClient = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
        ProcessorWatchdogValues values = new ProcessorWatchdogValues(11L, 4L, 3, 1, 7, 10_000, 9L, 60_000, 1_024, 5L, 2L, 0L);
        when(jmxClient.processorMetrics().observation()).thenReturn(Optional.of(
                new ProcessorMetricsObservation(
                        "agent-instance",
                        new JmxOwnerState.Present<>(values, 2L),
                        new JmxOwnerState.Absent<>(0L),
                        new JmxOwnerState.Absent<>(0L))));
        TracingBootstrapSettings settings = new TracingBootstrapSettings(
                true,
                TracingConfigurationAuthority.HELM,
                PlatformEnvironmentPosture.LOCAL);
        TracingStatusAssembler assembler = new TracingStatusAssembler(
                NoopTraceOperations.INSTANCE,
                NoOpTracingRuntime.noop(),
                settings,
                jmxClient,
                Optional::empty,
                diagnostics(AgentRuntimeState.AGENT_READY),
                stableCoherenceGuard());

        // when
        TracingStatusSnapshot snapshot = assembler.assemble();

        // then
        assertThat(snapshot.schemaVersion()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> watchdog = (Map<String, Object>) snapshot.runtime().get("watchdog");
        assertThat(watchdog)
                .containsEntry("present", true)
                .containsEntry("forcedSpanCloses", 11L)
                .containsEntry("forcedTraceCloses", 4L)
                .containsEntry("activeSpanCount", 3)
                .containsEntry("activeTraceCount", 1)
                .containsEntry("trackedTraceCount", 7)
                .containsEntry("trackedTraceCapacity", 10_000)
                .containsEntry("trackingEvictedTotal", 9L)
                .containsEntry("trackedSpanCapacity", 60_000)
                .containsEntry("trackedSpanPerTraceCapacity", 1_024)
                .containsEntry("spansRejectedPerTraceTotal", 5L)
                .containsEntry("spansRejectedGlobalTotal", 2L)
                .containsEntry("spanLedgerInvariantViolationTotal", 0L);
    }

    @Test
    void mixedReadinessGenerationIsNeverPublishedAsReadyStatus() throws Exception {
        // given
        MBeanServer server = mock(MBeanServer.class);
        when(server.getMBeanInfo(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME))
                .thenReturn(ReadinessJmxTestFixtures.metadata());
        when(server.getAttribute(
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT))
                .thenReturn(
                        ReadinessJmxTestFixtures.readySnapshot("runtime-epoch-1", 1L, "batch-1"),
                        ReadinessJmxTestFixtures.readySnapshot("runtime-epoch-1", 1L, "batch-1"),
                        ReadinessJmxTestFixtures.readySnapshot("runtime-epoch-2", 2L, "batch-2")
                );

        TracingBootstrapSettings settings = new TracingBootstrapSettings(
                true,
                TracingConfigurationAuthority.CONFIG_SERVER,
                PlatformEnvironmentPosture.LOCAL
        );

        AcceptedSettingsReadModel projection = new AcceptedSettingsReadModel(
                new ExpectedSettingsRevision("runtime-1", 7L),
                new AcceptedPolicySummary(true, 0.25, 3, true, false, true, true, "INFO")
        );

        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        TracingStatusAssembler assembler = new TracingStatusAssembler(
                NoopTraceOperations.INSTANCE,
                NoOpTracingRuntime.noop(),
                settings,
                mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS),
                () -> Optional.of(projection),
                diagnostics(AgentRuntimeState.AGENT_READY),
                coherenceGuard
        );

        // when
        TracingStatusSnapshot snapshot = assembler.assemble();

        // then
        assertThat(snapshot.agent())
                .containsEntry("state", AgentRuntimeState.EXTENSION_INCOMPATIBLE.name())
                .containsEntry("failureCode", AgentReadinessCoherenceGuard.MIXED_GENERATION);
        assertThat(snapshot.runtime()).isEmpty();
        assertThat(snapshot.settings().available()).isFalse();
        assertThat(snapshot.settings().reason()).isEqualTo(SettingsAbsenceReason.AGENT_UNAVAILABLE);
        assertThat(snapshot.jmxContractDiagnostics()).isEmpty();
    }

    private static TracingStatusAssembler assembler(boolean enabled,
                                                    TracingConfigurationAuthority authority,
                                                    TracingDeploymentDiagnostics diagnostics) {
        return assembler(enabled, authority, new AtomicReference<>(diagnostics), null);
    }

    private static TracingStatusAssembler assembler(boolean enabled,
                                                    TracingConfigurationAuthority authority,
                                                    AtomicReference<TracingDeploymentDiagnostics> diagnostics,
                                                    AcceptedSettingsReadModel projection) {
        TracingBootstrapSettings settings = new TracingBootstrapSettings(
                enabled,
                authority,
                PlatformEnvironmentPosture.LOCAL);

        TracingRuntime runtime = enabled
                ? NoOpTracingRuntime.noop()
                : NoOpTracingRuntime.disabledByConfiguration("test-disabled");

        return new TracingStatusAssembler(
                NoopTraceOperations.INSTANCE,
                runtime,
                settings,
                mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS),
                () -> Optional.ofNullable(projection),
                diagnostics::get,
                stableCoherenceGuard()
        );
    }

    private static AgentReadinessCoherenceGuard stableCoherenceGuard() {
        try {
            MBeanServer server = mock(MBeanServer.class);
            when(server.getMBeanInfo(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME))
                    .thenReturn(ReadinessJmxTestFixtures.metadata());

            when(server.getAttribute(
                    JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                    JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT))
                    .thenReturn(ReadinessJmxTestFixtures.readySnapshot("runtime-epoch-1", 1L, "batch-1"));

            AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
            AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
            return coherenceGuard;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot prepare stable readiness coherence guard", exception);
        }
    }

    private static TracingDeploymentDiagnostics diagnostics(AgentRuntimeState state) {
        return new TracingDeploymentDiagnostics(
                state == AgentRuntimeState.AGENT_READY,
                state == AgentRuntimeState.AGENT_READY,
                state,
                new TracingDeploymentDiagnostics.AgentReadiness(
                        state == AgentRuntimeState.AGENT_READY,
                        new TracingDeploymentDiagnostics.ContractIdentity(
                                "platform-tracing-jmx-wire-contract",
                                "schema-digest"),
                        new TracingDeploymentDiagnostics.ReadinessAnchor(
                                "runtime-epoch-1",
                                1L,
                                "batch-1"),
                        new TracingDeploymentDiagnostics.LifecycleState(
                                state == AgentRuntimeState.AGENT_READY ? "READY" : "FAILED",
                                state == AgentRuntimeState.AGENT_READY ? "" : "TEST_FAILURE",
                                Set.of(),
                                Set.of()),
                        new TracingDeploymentDiagnostics.DistributionIdentity(
                                "a".repeat(64),
                                "2.30.0",
                                "1.2.3"),
                        new TracingDeploymentDiagnostics.ClassloaderTopology(
                                "agent-loader@1",
                                "sdk-loader@2")
                )
        );
    }
}
