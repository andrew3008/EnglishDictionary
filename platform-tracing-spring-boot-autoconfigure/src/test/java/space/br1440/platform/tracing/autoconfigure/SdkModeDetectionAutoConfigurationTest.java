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
import space.br1440.platform.tracing.autoconfigure.support.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.support.SdkMode;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;
import space.br1440.platform.tracing.core.facade.NoopTraceOperations;

class SdkModeDetectionAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingCoreAutoConfiguration.class));

    @Test
    void defaultAgentModeWithoutControlledAgentFailsStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                            "platform.tracing.sdk.mode=AGENT rejected observed runtime state=AGENT_MISSING, "
                                    + "failureCode=CONTROLLED_AGENT_MISSING");
        });
    }

    @Test
    void disabledWithoutAnyRuntimeCreatesTheOnlySuccessfulNoopFacade() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.enabled=false",
                        "platform.tracing.sdk.mode=DISABLED")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(NoopTraceOperations.class);
                    SdkModeDiagnostics diagnostics = context.getBean(SdkModeDiagnostics.class);
                    assertThat(diagnostics.mode()).isEqualTo(SdkMode.DISABLED);
                    assertThat(diagnostics.runtimeState()).isEqualTo(AgentRuntimeState.DISABLED);
                    assertThat(context).doesNotHaveBean(OpenTelemetry.class);
                });
    }

    @Test
    void applicationSdkBeanIsRejectedEvenWhenDisabled() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .withPropertyValues(
                        "platform.tracing.enabled=false",
                        "platform.tracing.sdk.mode=DISABLED")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessage(
                                    "Application-owned OpenTelemetry bean is not supported with Controlled Agent "
                                            + "ownership: openTelemetry");
                });
    }

    @Test
    void contradictoryEnabledAndModeValuesFailStartup() {
        contextRunner
                .withPropertyValues("platform.tracing.enabled=true", "platform.tracing.sdk.mode=DISABLED")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(
                                "Contradictory platform tracing configuration: platform.tracing.enabled=true "
                                        + "requires platform.tracing.sdk.mode=AGENT"));
        contextRunner
                .withPropertyValues("platform.tracing.enabled=false", "platform.tracing.sdk.mode=AGENT")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(
                                "Contradictory platform tracing configuration: platform.tracing.enabled=false "
                                        + "requires platform.tracing.sdk.mode=DISABLED"));
    }

    @Test
    void removedPropertyValuesFailBinding() {
        for (String removed : new String[] {"AUTO", "STARTER", "EXTERNAL"}) {
            contextRunner
                    .withPropertyValues("platform.tracing.sdk.mode=" + removed)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseMessage(
                                        "No enum constant space.br1440.platform.tracing.autoconfigure.support.SdkMode."
                                                + removed);
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
