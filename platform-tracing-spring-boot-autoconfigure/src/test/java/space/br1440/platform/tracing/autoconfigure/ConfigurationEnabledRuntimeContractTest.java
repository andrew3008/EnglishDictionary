package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.autoconfigure.agent.AgentExtensionDescriptor;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationAuthorityAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.TracingDiagnosticsActuatorAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.TracingRuntimeDiagnosticsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.health.TracingHealthIndicator;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;

class ConfigurationEnabledRuntimeContractTest {

    private static final String CONFIGURATION_DISABLED_REASON =
            "platform.tracing.enabled=false";

    private final ApplicationContextRunner runtimeRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class))
            .withPropertyValues(
                    "platform.tracing.configuration.authority=HELM",
                    "platform.environment=LOCAL");

    private final ApplicationContextRunner observableRuntimeRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class,
                    TracingRuntimeDiagnosticsAutoConfiguration.class,
                    TracingDiagnosticsActuatorAutoConfiguration.class,
                    TracingStatusAutoConfiguration.class))
            .withPropertyValues(
                    "platform.tracing.configuration.authority=HELM",
                    "platform.environment=LOCAL");

    @Test
    void agentEnabledWithReadyAgentKeepsManualTracingOperational() {
        runtimeRunner
                .withInitializer(ControlledAgentTestRuntime::initializeWithExporter)
                .withPropertyValues("platform.tracing.enabled=true")
                .run(context -> assertManualTracingOperational(context, "agent-enabled"));
    }

    @Test
    void tracingDisabledWithReadyAgentFailsDeploymentContract() {
        try (ControlledAgentTestRuntime ignored = ControlledAgentTestRuntime.start()) {
            observableRuntimeRunner
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("AGENT_READY");
                });
        }
    }

    @Test
    void tracingDisabledWithoutAgentCreatesNoopFacade() {
        runtimeRunner
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TracingBootstrapSettings.class).tracingEnabled()).isFalse();
                    assertDisabledRuntime(context.getBean(TracingRuntime.class), CONFIGURATION_DISABLED_REASON);
                });
    }

    @Test
    void tracingDisabledWithIncompatibleExtensionStillFailsDeploymentContract() {
        try (ControlledAgentTestRuntime ignored = ControlledAgentTestRuntime.startIncompatible()) {
            runtimeRunner
                    .withPropertyValues("platform.tracing.enabled=false")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("EXTENSION_INCOMPATIBLE");
                    });
        }
    }

    @Test
    void omittedEnabledKeepsAgentBaselineOperational() {
        runtimeRunner
                .withInitializer(ControlledAgentTestRuntime::initializeWithExporter)
                .run(context -> {
                    assertThat(context.getBean(TracingBootstrapSettings.class).tracingEnabled()).isTrue();
                    assertManualTracingOperational(context, "configuration-enabled-default");
                });
    }

    @Test
    void healthIsUpWhenAgentIsReadyAndRuntimeEnabled() {
        observableRuntimeRunner
                .withInitializer(ControlledAgentTestRuntime::initializeWithExporter)
                .withPropertyValues("platform.tracing.enabled=true")
                .run(context -> {
                    Health health = context.getBean(TracingHealthIndicator.class).health();

                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails())
                            .containsEntry("reasonCode", "OPERATIONAL")
                            .containsEntry("tracingEnabled", true)
                            .containsEntry("platformRuntimeMode", TracingRuntimeMode.ENABLED.name())
                            .containsEntry("agentState", AgentRuntimeState.AGENT_READY.name())
                            .doesNotContainKey("failureCode");
                });
    }

    @Test
    void intentionallyDisabledTracingDoesNotDegradeHealth() {
        observableRuntimeRunner
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    Health health = context.getBean(TracingHealthIndicator.class).health();

                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails())
                            .containsEntry("reasonCode", "CONFIGURED_DISABLED")
                            .containsEntry("tracingEnabled", false)
                            .containsEntry(
                                    "platformRuntimeMode",
                                    TracingRuntimeMode.DISABLED_BY_CONFIGURATION.name())
                            .containsEntry("agentState", AgentRuntimeState.DISABLED.name());
                });
    }

    @Test
    void healthIsDownWhenStartupContractInvariantIsViolated() {
        NoOpTracingRuntime runtime = NoOpTracingRuntime.unavailable("test-agent-runtime-failure");
        AgentExtensionDescriptor descriptor = new AgentExtensionDescriptor(
                AgentRuntimeState.EXTENSION_FAILED,
                true,
                true,
                "development",
                1,
                "platform-agent-secure-v1",
                "FAILED",
                "TEST_AGENT_FAILURE",
                Set.of());
        TracingDeploymentDiagnostics diagnostics = new TracingDeploymentDiagnostics(
                true,
                true,
                descriptor.state(),
                descriptor);

        Health health = new TracingHealthIndicator(runtime, diagnostics).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("reasonCode", "STARTUP_CONTRACT_VIOLATED")
                .containsEntry("agentState", AgentRuntimeState.EXTENSION_FAILED.name())
                .containsEntry("failureCode", "TEST_AGENT_FAILURE");
        assertThat(health.getDetails().values()).doesNotContain("test-agent-runtime-failure");
    }

    private static void assertManualTracingOperational(
            AssertableApplicationContext context,
            String spanName) {
        assertThat(context).hasNotFailed();
        TracingRuntime runtime = context.getBean(TracingRuntime.class);
        assertThat(runtime.state().mode())
                .isEqualTo(TracingRuntimeMode.ENABLED);

        TraceOperations operations = context.getBean(TraceOperations.class);
        InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
        int before = exporter.getFinishedSpanItems().size();
        startManualSpan(operations, spanName);
        assertThat(exporter.getFinishedSpanItems()).hasSize(before + 1);
    }

    private static void startManualSpan(TraceOperations operations, String spanName) {
        SpanSpec spec = SpanSpec.builder(spanName)
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .detached()
                .build();
        operations.spans().fromSpec(spec).start().close();
    }

    private static void assertDisabledRuntime(TracingRuntime runtime, String expectedReason) {
        assertThat(runtime.state().mode())
                .isEqualTo(
                        TracingRuntimeMode.DISABLED_BY_CONFIGURATION);
        assertThat(runtime.state().reason()).contains(expectedReason);
    }
}
