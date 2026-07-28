package space.br1440.platform.tracing.autoconfigure.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;

class TracingPolicyAdaptersActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AcceptedTracingPolicyReader.class, () -> () -> accepted())
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
                    assertThat(context).hasSingleBean(AcceptedTracingPolicyReader.class);
                    assertThat(context).hasSingleBean(TracingConfigurationReadModel.class);
                    assertThat(context).doesNotHaveBean(TracingPolicySnapshotClient.class);
                    assertThat(context).doesNotHaveBean(SpringActuatorTracingPolicyAdapter.class);
                    assertThat(context).doesNotHaveBean(TracingActuatorControlController.class);
                    assertThat(context.getBean(TracingConfigurationReadModel.class).read())
                            .containsEntry("authority", "HELM")
                            .containsEntry("acceptedSequence", 9L);
                });
    }

    @Test
    void actuatorSharedAndProductionFailBeforeWriterDiscovery() {
        assertRejectedPosture("SHARED");
        assertRejectedPosture("PRODUCTION");
    }

    @Test
    void actuatorLocalCreatesWriterAndReadProjectionFromActualAcceptedState() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.mode=AGENT",
                        "platform.tracing.configuration.authority=ACTUATOR",
                        "platform.environment=LOCAL")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TracingPolicySnapshotClient.class);
                    assertThat(context).hasSingleBean(SpringActuatorTracingPolicyAdapter.class);
                    assertThat(context).doesNotHaveBean(TracingActuatorControlController.class);
                    assertThat(context.getBean(TracingConfigurationReadModel.class).read())
                            .containsEntry("authority", "ACTUATOR")
                            .containsEntry("acceptedSequence", 9L);
                });
    }

    @Test
    void configServerInitialManifestFailureIsStartupFatal() {
        new ApplicationContextRunner()
                .withBean(SmartInitializingSingleton.class, () -> () ->
                        TracingPolicyAdaptersAutoConfiguration
                                .requireSuccessfulInitialDelivery(
                                        new TracingPolicyDeliveryResult(
                                                TracingPolicyDeliveryOutcome.REJECTED,
                                                "",
                                                "MISSING_OR_NON_AUTHORITATIVE_FIELD",
                                                9L)))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessage(
                                    "Initial Config Server tracing policy delivery failed: "
                                            + "MISSING_OR_NON_AUTHORITATIVE_FIELD");
                });
    }

    @Test
    void invalidEnvironmentTokenFailsBeforeCapabilityActivation() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.mode=AGENT",
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=PRODCUTION")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "Unsupported platform.environment value: PRODCUTION");
                });
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
                    assertThat(context).doesNotHaveBean(TracingActuatorControlController.class);
                });
    }

    @Test
    void profileNameAloneCannotActivateControlSurface() {
        contextRunner
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingActuatorControlController.class);
                });
    }

    private void assertRejectedPosture(String environment) {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.mode=AGENT",
                        "platform.tracing.configuration.authority=ACTUATOR",
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

    private static AcceptedTracingPolicyView accepted() {
        CompleteTracingPolicySnapshot policy = new CompleteTracingPolicySnapshot(
                true,
                0.1d,
                Map.of(),
                true,
                false,
                true,
                true,
                OtelAgentLogLevel.INFO);
        return new AcceptedTracingPolicyView(
                UUID.randomUUID().toString(),
                9L,
                "a".repeat(64),
                policy,
                "APPLIED",
                UUID.randomUUID().toString(),
                "NONE",
                false,
                false);
    }
}
