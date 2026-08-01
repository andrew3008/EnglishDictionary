package space.br1440.platform.tracing.autoconfigure.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.policy.AcceptedPolicyLeaves;
import space.br1440.platform.tracing.autoconfigure.control.policy.AcceptedPolicyProjection;
import space.br1440.platform.tracing.autoconfigure.control.policy.AgentReadinessView;
import space.br1440.platform.tracing.autoconfigure.bootstrap.PlatformEnvironmentPosture;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.agent.AgentExtensionDescriptor;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingMode;
import space.br1440.platform.tracing.autoconfigure.agent.TracingModeDiagnostics;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;

class TracingStatusAssemblerTest {

    @Test
    void reasonPriorityStartsWithDisabledThenAuthority() {
        TracingStatusAssembler disabled = assembler(
                false,
                TracingConfigurationAuthority.HELM,
                descriptor(AgentRuntimeState.AGENT_MISSING),
                Optional.empty());
        TracingStatusAssembler helm = assembler(
                true,
                TracingConfigurationAuthority.HELM,
                descriptor(AgentRuntimeState.AGENT_MISSING),
                Optional.empty());

        assertThat(disabled.assemble().policy().reason())
                .isEqualTo(PolicyAvailabilityReason.TRACING_DISABLED);
        assertThat(helm.assemble().policy().reason())
                .isEqualTo(PolicyAvailabilityReason.AUTHORITY_HAS_NO_ACCEPTED_POLICY);
    }

    @Test
    void writeAuthorityDistinguishesAgentUnavailableAndPolicyNotAccepted() {
        TracingStatusAssembler unavailable = assembler(
                true,
                TracingConfigurationAuthority.CONFIG_SERVER,
                descriptor(AgentRuntimeState.EXTENSION_FAILED),
                Optional.empty());
        TracingStatusAssembler notAccepted = assembler(
                true,
                TracingConfigurationAuthority.CONFIG_SERVER,
                descriptor(AgentRuntimeState.AGENT_READY),
                Optional.empty());

        assertThat(unavailable.assemble().policy().reason())
                .isEqualTo(PolicyAvailabilityReason.AGENT_UNAVAILABLE);
        assertThat(notAccepted.assemble().policy().reason())
                .isEqualTo(PolicyAvailabilityReason.POLICY_NOT_ACCEPTED);
    }

    @Test
    void acceptedPolicyIsSecretSafeAndPostStartAgentLossIsObserved() {
        AtomicReference<AgentExtensionDescriptor> state =
                new AtomicReference<>(descriptor(AgentRuntimeState.AGENT_READY));
        AcceptedPolicyProjection projection = new AcceptedPolicyProjection(
                new AgentReadinessView("runtime-1", 7L, true),
                new AcceptedPolicyLeaves(true, 0.25, 3, true, false, true, true, "INFO"));
        TracingStatusAssembler assembler = assembler(
                true,
                TracingConfigurationAuthority.CONFIG_SERVER,
                state,
                Optional.of(projection));

        TracingStatusSnapshot initial = assembler.assemble();
        assertThat(initial.policy().available()).isTrue();
        assertThat(initial.policy().accepted().samplingRouteRatiosCount()).isEqualTo(3);

        state.set(descriptor(AgentRuntimeState.AGENT_MISSING));
        TracingStatusSnapshot degraded = assembler.assemble();
        assertThat(degraded.policy().available()).isFalse();
        assertThat(degraded.policy().reason()).isEqualTo(PolicyAvailabilityReason.AGENT_UNAVAILABLE);
    }

    private static TracingStatusAssembler assembler(
            boolean enabled,
            TracingConfigurationAuthority authority,
            AgentExtensionDescriptor descriptor,
            Optional<AcceptedPolicyProjection> projection) {
        return assembler(enabled, authority, new AtomicReference<>(descriptor), projection);
    }

    private static TracingStatusAssembler assembler(
            boolean enabled,
            TracingConfigurationAuthority authority,
            AtomicReference<AgentExtensionDescriptor> descriptor,
            Optional<AcceptedPolicyProjection> projection) {
        TracingBootstrapSettings settings = new TracingBootstrapSettings(
                enabled ? TracingMode.AGENT : TracingMode.DISABLED,
                authority,
                PlatformEnvironmentPosture.LOCAL,
                enabled);
        TracingMode mode = enabled ? TracingMode.AGENT : TracingMode.DISABLED;
        TracingModeDiagnostics diagnostics = new TracingModeDiagnostics(
                mode,
                enabled,
                descriptor.get().state(),
                descriptor.get());
        return new TracingStatusAssembler(
                NoopTraceOperations.INSTANCE,
                settings,
                mock(PlatformTracingJmxClient.class),
                () -> projection,
                diagnostics,
                descriptor::get);
    }

    private static AgentExtensionDescriptor descriptor(AgentRuntimeState state) {
        return new AgentExtensionDescriptor(
                state,
                state == AgentRuntimeState.AGENT_READY,
                state == AgentRuntimeState.AGENT_READY,
                "development",
                1,
                "platform-agent-secure-v1",
                state == AgentRuntimeState.AGENT_READY ? "READY" : "FAILED",
                state == AgentRuntimeState.AGENT_READY ? "" : "TEST_FAILURE",
                Set.of());
    }
}
