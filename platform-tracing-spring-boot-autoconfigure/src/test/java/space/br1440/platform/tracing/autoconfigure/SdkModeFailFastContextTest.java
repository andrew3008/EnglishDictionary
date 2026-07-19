package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;

class SdkModeFailFastContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingCoreAutoConfiguration.class));

    @Test
    void explicitAgentWithoutMarkerFailsStartup() {
        contextRunner
                .withPropertyValues("platform.tracing.sdk.mode=AGENT")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "platform.tracing.sdk.mode=AGENT requires an active OpenTelemetry Java Agent marker");
                });
    }

    @Test
    void explicitExternalWithoutRuntimeFailsStartup() {
        contextRunner
                .withPropertyValues("platform.tracing.sdk.mode=EXTERNAL")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "platform.tracing.sdk.mode=EXTERNAL requires a functional external OpenTelemetry runtime");
                });
    }

    @Test
    void explicitStarterWithExternalBeanFailsStartup() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .withPropertyValues("platform.tracing.sdk.mode=STARTER")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "platform.tracing.sdk.mode=STARTER conflicts with an external OpenTelemetry runtime; "
                                            + "use AUTO or EXTERNAL");
                });
    }

    @Configuration
    static class OpenTelemetryConfiguration {

        @Bean
        OpenTelemetry openTelemetry() {
            return OpenTelemetrySdk.builder().build();
        }
    }
}
