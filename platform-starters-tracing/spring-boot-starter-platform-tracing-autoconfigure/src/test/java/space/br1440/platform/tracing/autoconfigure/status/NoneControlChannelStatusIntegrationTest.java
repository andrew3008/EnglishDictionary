package space.br1440.platform.tracing.autoconfigure.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.AgentStartupVerifier;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxReadAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup.TracingStartupContractHealthAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;

class NoneControlChannelStatusIntegrationTest {

    @Test
    void acceptedBootstrapSnapshotIsVisibleThroughNoneStatus() throws Exception {
        // given
        try (FakeAgentPlatformJmxPlane ignored = FakeAgentPlatformJmxPlane.compliant(
                "128bcc0c-8f1f-499d-9cbb-6ca73a99dc17",
                7L)) {
            // when
            runNoneContext(context -> {
                TracingStatusSnapshot snapshot = assembler(context).assemble();

                // then
                assertThat(snapshot.settings().available()).isTrue();
                assertThat(snapshot.settings().acceptedRevision().acceptedSequence()).isEqualTo(7L);
                assertThat(snapshot.settings().accepted().samplingEnabled()).isTrue();
                assertThat(snapshot.settings().accepted().samplingRatio()).isEqualTo(0.1d);
                assertThat(snapshot.settings().accepted().samplingRouteRatios()).isEmpty();
                assertThat(snapshot.settings().sourceAuthority()).isEqualTo("CONFIG_SERVER");
                assertThat(snapshot.settings().accepted().validationLevel()).isEqualTo("WARNING");
                assertThat(snapshot.settings().accepted().exporterEnabled()).isTrue();
                assertThat(snapshot.settings().accepted().traceControlHeadersEnabled()).isTrue();
                assertThat(snapshot.settings().accepted().diagnosticsLogLevel()).isEqualTo("INFO");
            });
        }
    }

    @Test
    void missingAcceptedSnapshotKeepsContextUpAndReportsSettingsUnreadable() throws Exception {
        // given
        try (FakeAgentPlatformJmxPlane ignored = FakeAgentPlatformJmxPlane.missingAcceptedPolicySnapshot(
                "0355b57c-905d-4b89-a0ad-2f96370732d7")) {
            // when
            runNoneContext(context -> {
                TracingStatusSnapshot snapshot = assembler(context).assemble();

                // then
                assertThat(context).hasNotFailed();
                assertThat(snapshot.settings().available()).isFalse();
                assertThat(snapshot.settings().reason()).isEqualTo(SettingsAbsenceReason.SETTINGS_UNREADABLE);
                assertThat(context.getBean(AgentReadinessCoherenceGuard.class).terminalFailureCode()).isNull();
            });
        }
    }

    @Test
    void unreadableAcceptedSnapshotKeepsContextUpAndRepeatedlyReportsSettingsUnreadable() throws Exception {
        // given
        try (FakeAgentPlatformJmxPlane ignored = FakeAgentPlatformJmxPlane.readFailingAcceptedPolicySnapshot(
                "70dfb394-9718-451f-a3c0-de1a0509910d")) {
            // when
            runNoneContext(context -> {
                TracingStatusAssembler assembler = assembler(context);
                TracingStatusSnapshot first = assembler.assemble();
                TracingStatusSnapshot second = assembler.assemble();

                // then
                assertThat(context).hasNotFailed();
                assertThat(first.settings().reason()).isEqualTo(SettingsAbsenceReason.SETTINGS_UNREADABLE);
                assertThat(second.settings().reason()).isEqualTo(SettingsAbsenceReason.SETTINGS_UNREADABLE);
                assertThat(context.getBean(AgentReadinessCoherenceGuard.class).terminalFailureCode()).isNull();
            });
        }
    }

    @Test
    void coherenceFailureLatchesTrustWithoutChangingStartupContractHealth() throws Exception {
        // given
        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.compliant(
                "e89ccdee-0d8d-4c49-b441-540a7071719d",
                7L)) {
            runNoneContext(context -> {
                HealthIndicator startupContract = context.getBean(
                        "tracingStartupContractHealthIndicator",
                        HealthIndicator.class
                );
                Health healthBeforeFailure = startupContract.health();
                agent.restartWithNewInstance("4134a8a7-57d9-4963-9a45-35e797005a8a", 0L);

                // when
                TracingStatusSnapshot snapshot = assembler(context).assemble();
                Health healthAfterFailure = startupContract.health();

                // then
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(AgentReadinessCoherenceGuard.class).terminalFailureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.PINNED_ANCHOR_CHANGED);
                assertThat(snapshot.settings().reason()).isEqualTo(SettingsAbsenceReason.AGENT_UNAVAILABLE);
                assertThat(snapshot.agent())
                        .containsEntry("state", AgentRuntimeState.EXTENSION_INCOMPATIBLE.name())
                        .containsEntry("failureCode", AgentReadinessCoherenceGuard.PINNED_ANCHOR_CHANGED);
                assertThat((String) snapshot.agent().get("failureCode")).isNotBlank();
                assertThat(healthAfterFailure.getStatus())
                        .isEqualTo(healthBeforeFailure.getStatus())
                        .isEqualTo(Status.UP);
                assertThat(healthAfterFailure.getDetails()).isEqualTo(healthBeforeFailure.getDetails());
            });
        }
    }

    private static void runNoneContext(ContextConsumer<AssertableApplicationContext> assertions) {
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard();
        TracingDeploymentDiagnostics diagnostics = new AgentStartupVerifier(coherenceGuard, () -> "1.2.3")
                .verify(true, true, false, true);
        TracingRuntime healthRuntime = mock(TracingRuntime.class, RETURNS_DEEP_STUBS);
        when(healthRuntime.state().mode()).thenReturn(TracingRuntimeMode.ENABLED);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingControlChannelAutoConfiguration.class,
                        PlatformJmxReadAutoConfiguration.class,
                        TracingStartupContractHealthAutoConfiguration.class)
                )
                .withBean(AgentReadinessCoherenceGuard.class, () -> coherenceGuard)
                .withBean(TracingDeploymentDiagnostics.class, () -> diagnostics)
                .withBean(TracingRuntime.class, () -> healthRuntime)
                .withPropertyValues("platform.tracing.control.channel=NONE")
                .run(assertions);
    }

    private static TracingStatusAssembler assembler(AssertableApplicationContext context) {
        PlatformTracingJmxClient jmx = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
        when(jmx.jmxContractDiagnostics()).thenReturn(List.of());
        return new TracingStatusAssembler(
                NoopTraceOperations.INSTANCE,
                NoOpTracingRuntime.noop(),
                context.getBean(TracingBootstrapSettings.class),
                jmx,
                context.getBean(AcceptedSettingsStatusReader.class),
                context.getBean(TracingDeploymentDiagnostics.class),
                context.getBean(AgentReadinessCoherenceGuard.class));
    }
}
