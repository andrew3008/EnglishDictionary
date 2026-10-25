package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.TracingRuntimeDiagnosticsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup.TracingStartupContractHealthAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;

class ConfigurationEnabledRuntimeContractTest {

    private static final String CONFIGURATION_DISABLED_REASON = "platform.tracing.enabled=false";

    private final ApplicationContextRunner runtimeRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class))
            .withPropertyValues("platform.tracing.control.channel=NONE");

    private final ApplicationContextRunner observableRuntimeRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class,
                    TracingRuntimeDiagnosticsAutoConfiguration.class,
                    TracingStartupContractHealthAutoConfiguration.class,
                    TracingStatusAutoConfiguration.class)
            )
            .withPropertyValues("platform.tracing.control.channel=NONE");

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
    void contextFailsOnExtensionFailedSnapshot() {
        // given
        String failureCode = "PLATFORM_SAMPLER_REPLACED";

        try (ControlledAgentTestRuntime ignored = ControlledAgentTestRuntime.startFailed(failureCode)) {
            // when
            runtimeRunner
                    .withPropertyValues("platform.tracing.enabled=true")
                    .run(context -> {
                        // then
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(IllegalStateException.class)
                                .rootCause()
                                .hasMessageContaining("runtimeState=EXTENSION_FAILED")
                                .hasMessageContaining("reason=" + failureCode);
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

    @SuppressWarnings("SameParameterValue")
    private static void assertDisabledRuntime(TracingRuntime runtime, String expectedReason) {
        assertThat(runtime.state().mode())
                .isEqualTo(TracingRuntimeMode.DISABLED_BY_CONFIGURATION);

        assertThat(runtime.state().reason()).contains(expectedReason);
    }
}
