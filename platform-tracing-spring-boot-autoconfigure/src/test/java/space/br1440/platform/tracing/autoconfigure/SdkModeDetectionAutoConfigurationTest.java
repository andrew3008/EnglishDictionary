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
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingMode;
import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationAuthorityAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.agent.TracingModeDiagnostics;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;

class TracingModeDetectionAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class))
            .withPropertyValues(
                    "platform.tracing.configuration.authority=HELM",
                    "platform.environment=LOCAL");

    @Test
    void defaultAgentModeWithoutControlledAgentFailsStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                            "platform.tracing.mode=AGENT rejected observed runtime state=AGENT_MISSING, "
                                    + "failureCode=CONTROLLED_AGENT_MISSING");
        });
    }

    @Test
    void disabledWithoutAnyRuntimeCreatesTheOnlySuccessfulNoopFacade() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.mode=DISABLED")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(NoopTraceOperations.class);
                    TracingModeDiagnostics diagnostics = context.getBean(TracingModeDiagnostics.class);
                    assertThat(diagnostics.mode()).isEqualTo(TracingMode.DISABLED);
                    assertThat(diagnostics.runtimeState()).isEqualTo(AgentRuntimeState.DISABLED);
                    assertThat(context).doesNotHaveBean(OpenTelemetry.class);
                });
    }

    @Test
    void applicationSdkBeanIsRejectedEvenWhenDisabled() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .withPropertyValues(
                        "platform.tracing.mode=DISABLED")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessage(
                                    "Application-owned OpenTelemetry bean is not supported with Controlled Agent "
                                            + "ownership: openTelemetry");
                });
    }

    @Test
    void removedPropertyValuesFailBinding() {
        for (String removed : new String[] {"AUTO", "STARTER", "EXTERNAL"}) {
            contextRunner
                    .withPropertyValues("platform.tracing.mode=" + removed)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("Invalid property 'platform.tracing.mode'='" + removed + "'");
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
