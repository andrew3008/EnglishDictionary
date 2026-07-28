package space.br1440.platform.tracing.autoconfigure.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TracingPolicyAdaptersActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingPolicyAdaptersAutoConfiguration.class));

    @Test
    void helmHasNoRuntimeSnapshotClientOrActuatorWriteSurface() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.mode=AGENT",
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=PRODUCTION")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingPolicySnapshotClient.class);
                    assertThat(context).doesNotHaveBean(SpringActuatorTracingPolicyAdapter.class);
                    assertThat(context).doesNotHaveBean(TracingActuatorControlEndpoint.class);
                });
    }

    @Test
    void actuatorSharedAndProductionFailBeforeWriterDiscovery() {
        assertRejectedPosture("SHARED");
        assertRejectedPosture("PRODUCTION");
    }

    @Test
    void disabledHelmDoesNotDiscoverPrivateWriter() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.mode=DISABLED",
                        "platform.tracing.configuration.authority=HELM")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PrivateControlTracingPolicyClient.class);
                    assertThat(context).doesNotHaveBean(TracingActuatorControlEndpoint.class);
                });
    }

    @Test
    void profileNameAloneCannotActivateControlSurface() {
        contextRunner
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingActuatorControlEndpoint.class);
                });
    }

    private void assertRejectedPosture(String environment) {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.mode=AGENT",
                        "platform.tracing.configuration.authority=ACTUATOR",
                        "platform.tracing.actuator.control-enabled=true",
                        "platform.environment=" + environment)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "Unsupported tracing configuration posture: "
                                            + "mode=AGENT, authority=ACTUATOR; "
                                            + "ACTUATOR разрешён только при "
                                            + "platform.environment=LOCAL");
                });
    }
}
