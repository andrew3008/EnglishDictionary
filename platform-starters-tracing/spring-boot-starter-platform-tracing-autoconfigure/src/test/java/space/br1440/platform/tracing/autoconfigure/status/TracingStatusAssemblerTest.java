package space.br1440.platform.tracing.autoconfigure.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsLeaves;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsReadModel;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AgentReadinessSnapshot;
import space.br1440.platform.tracing.autoconfigure.bootstrap.PlatformEnvironmentPosture;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

class TracingStatusAssemblerTest {

    @Test
    void reasonPriorityStartsWithDisabledThenAuthority() {
        TracingStatusAssembler disabled = assembler(
                false,
                TracingConfigurationAuthority.HELM,
                diagnostics(AgentRuntimeState.AGENT_MISSING),
                Optional.empty());
        TracingStatusAssembler helm = assembler(
                true,
                TracingConfigurationAuthority.HELM,
                diagnostics(AgentRuntimeState.AGENT_MISSING),
                Optional.empty());

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
                diagnostics(AgentRuntimeState.EXTENSION_FAILED),
                Optional.empty());
        TracingStatusAssembler notAccepted = assembler(
                true,
                TracingConfigurationAuthority.CONFIG_SERVER,
                diagnostics(AgentRuntimeState.AGENT_READY),
                Optional.empty());

        assertThat(unavailable.assemble().settings().reason())
                .isEqualTo(SettingsAbsenceReason.AGENT_UNAVAILABLE);
        assertThat(notAccepted.assemble().settings().reason())
                .isEqualTo(SettingsAbsenceReason.SETTINGS_NOT_ACCEPTED);
    }

    @Test
    void acceptedPolicyIsSecretSafeAndPostStartAgentLossIsObserved() {
        AtomicReference<TracingDeploymentDiagnostics> state =
                new AtomicReference<>(diagnostics(AgentRuntimeState.AGENT_READY));
        AcceptedSettingsReadModel projection = new AcceptedSettingsReadModel(
                new AgentReadinessSnapshot("runtime-1", 7L, true),
                new AcceptedSettingsLeaves(true, 0.25, 3, true, false, true, true, "INFO"));
        TracingStatusAssembler assembler = assembler(
                true,
                TracingConfigurationAuthority.CONFIG_SERVER,
                state,
                Optional.of(projection));

        TracingStatusSnapshot initial = assembler.assemble();
        assertThat(initial.settings().available()).isTrue();
        assertThat(initial.settings().accepted().samplingRouteRatiosCount()).isEqualTo(3);

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
                Optional::<AcceptedSettingsReadModel>empty,
                diagnostics(AgentRuntimeState.AGENT_READY));

        @SuppressWarnings("unchecked")
        Map<String, Object> validation =
                (Map<String, Object>) assembler.assemble().runtime().get("validation");
        assertThat(validation).containsEntry("violationsTotal", 42L);
    }

    private static TracingStatusAssembler assembler(boolean enabled,
                                                    TracingConfigurationAuthority authority,
                                                    TracingDeploymentDiagnostics diagnostics,
                                                    Optional<AcceptedSettingsReadModel> projection) {
        return assembler(enabled, authority, new AtomicReference<>(diagnostics), projection);
    }

    private static TracingStatusAssembler assembler(boolean enabled,
                                                    TracingConfigurationAuthority authority,
                                                    AtomicReference<TracingDeploymentDiagnostics> diagnostics,
                                                    Optional<AcceptedSettingsReadModel> projection) {
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
                () -> projection,
                diagnostics::get);
    }

    private static TracingDeploymentDiagnostics diagnostics(AgentRuntimeState state) {
        return new TracingDeploymentDiagnostics(
                state == AgentRuntimeState.AGENT_READY,
                state == AgentRuntimeState.AGENT_READY,
                state,
                new TracingDeploymentDiagnostics.AgentReadiness(
                        state == AgentRuntimeState.AGENT_READY,
                        "development",
                        1,
                        state == AgentRuntimeState.AGENT_READY ? "READY" : "FAILED",
                        state == AgentRuntimeState.AGENT_READY ? "" : "TEST_FAILURE",
                        Set.of()));
    }
}
