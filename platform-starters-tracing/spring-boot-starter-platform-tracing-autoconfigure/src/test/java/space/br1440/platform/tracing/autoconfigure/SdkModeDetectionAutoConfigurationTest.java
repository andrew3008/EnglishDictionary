package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationAuthorityAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;

class TracingEnabledAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class)
            )
            .withPropertyValues(
                    "platform.tracing.configuration.authority=HELM"
            );

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
                            .doesNotContain("reason=NONE"));
        });
    }

    @Test
    void disabledWithoutAnyRuntimeCreatesTheOnlySuccessfulNoopFacade() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.enabled=false")
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
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessage(
                                    "Application-owned OpenTelemetry bean is not supported with Controlled Agent "
                                            + "ownership: openTelemetry");
                });
    }

    @Test
    void obsoleteModePropertyFailsFast() {
        for (String removed : new String[] {"AGENT", "DISABLED"}) {
            contextRunner
                    .withPropertyValues("platform.tracing.mode=" + removed)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("Obsolete Spring-only property 'platform.tracing.mode'");
                    });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OpenTelemetryConfiguration {

        @Bean
        OpenTelemetry openTelemetry() {
            return OpenTelemetrySdk.builder().build();
        }
    }
}
