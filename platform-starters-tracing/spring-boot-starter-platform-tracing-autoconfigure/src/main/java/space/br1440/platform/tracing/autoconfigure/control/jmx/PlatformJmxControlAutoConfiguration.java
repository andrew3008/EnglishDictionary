package space.br1440.platform.tracing.autoconfigure.control.jmx;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;

@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
@Conditional(PlatformJmxControlAutoConfiguration.PlatformJmxControlChannelCondition.class)
public class PlatformJmxControlAutoConfiguration {

    @Bean
    public PlatformJmxTracingSettingsClient tracingSettingsSnapshotClient(AgentReadinessCoherenceGuard coherenceGuard,
                                                                          TracingDeploymentDiagnostics startupDiagnostics) {
        if (startupDiagnostics.runtimeState() != AgentRuntimeState.AGENT_READY) {
            throw new TracingConfigurationGuardException(
                    "JMX_CLIENT_STARTUP_TRUST_NOT_ESTABLISHED: Controlled Agent startup verification "
                            + "must reach AGENT_READY before JMX control initialization. runtimeState="
                            + startupDiagnostics.runtimeState());
        }

        PlatformJmxTracingSettingsClient client = new PlatformJmxTracingSettingsClient(coherenceGuard);
        client.initialize();
        return client;
    }

        static final class PlatformJmxControlChannelCondition extends AnyNestedCondition {

        PlatformJmxControlChannelCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(
                name = "platform.tracing.control.channel",
                havingValue = "ACTUATOR"
        )
        @SuppressWarnings("unused")
        static class OnActuator {
        }

        @ConditionalOnProperty(
                name = "platform.tracing.control.channel",
                havingValue = "CONFIG_SERVER"
        )
        @SuppressWarnings("unused")
        static class OnConfigServer {
        }
    }
}
