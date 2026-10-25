package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;

class TracingEnabledAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class)
            )
            .withPropertyValues("platform.tracing.control.channel=NONE");

    @Test
    void enabledByDefaultWithoutControlledAgentFailsStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("runtimeState=AGENT_MISSING")
                    .hasMessageContaining("reason=CONTROLLED_AGENT_MISSING")
                    .hasMessageContaining("platform-tracing-javaagent")
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain("failureCode=NONE")
                            .doesNotContain("reason=NONE")
                    );
        });
    }

    @Test
    void disabledWithoutAnyRuntimeCreatesTheOnlySuccessfulNoopFacade() {
        contextRunner
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(NoopTraceOperations.class);

                    TracingDeploymentDiagnostics diagnostics = context.getBean(TracingDeploymentDiagnostics.class);
                    assertThat(diagnostics.tracingEnabled()).isFalse();
                    assertThat(diagnostics.runtimeState()).isEqualTo(AgentRuntimeState.DISABLED);
                    assertThat(context).doesNotHaveBean(OpenTelemetry.class);
                });
    }

    @Test
    void applicationSdkBeanIsRejectedEvenWhenDisabled() {
        String message = "Platform tracing startup rejected: runtimeState=DUAL_SDK_DETECTED, "
                + "reason=DUAL_SDK_COLLISION. Root cause: the application declares an OpenTelemetry bean, "
                + "but the Controlled Platform Agent already owns the OpenTelemetry runtime. "
                + "Conflicting bean: type=io.opentelemetry.api.OpenTelemetry, name=openTelemetry. "
                + "Remove the application-owned OpenTelemetry bean or its SDK auto-configuration";

        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessage(message);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class OpenTelemetryConfiguration {

        @Bean
        OpenTelemetry openTelemetry() {
            return OpenTelemetrySdk.builder().build();
        }
    }
}
