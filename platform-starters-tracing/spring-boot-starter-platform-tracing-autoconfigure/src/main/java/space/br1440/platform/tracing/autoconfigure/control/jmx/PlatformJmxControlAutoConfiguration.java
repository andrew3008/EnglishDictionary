package space.br1440.platform.tracing.autoconfigure.control.jmx;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannelCondition;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.status.AcceptedSettingsStatusReader;

@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
@Conditional(TracingControlChannelCondition.OnWritable.class)
public class PlatformJmxControlAutoConfiguration {

    @Bean
    public PlatformJmxTracingSettingsClient platformJmxTracingSettingsClient(AgentReadinessCoherenceGuard coherenceGuard,
                                                                             TracingDeploymentDiagnostics startupDiagnostics) {
        AgentRuntimeState runtimeState = startupDiagnostics.runtimeState();
        if (runtimeState != AgentRuntimeState.AGENT_READY) {
            throw new TracingConfigurationGuardException("""
                    JMX_CLIENT_STARTUP_TRUST_NOT_ESTABLISHED: Controlled Agent startup verification \
                    must reach AGENT_READY before JMX control initialization. runtimeState=%s\
                    """.formatted(runtimeState));
        }

        PlatformJmxTracingSettingsClient client = new PlatformJmxTracingSettingsClient(coherenceGuard);
        client.initialize();
        return client;
    }

    @Bean
    AcceptedSettingsStatusReader acceptedSettingsStatusReader(PlatformJmxTracingSettingsClient client) {
        return client.statusReader();
    }
}
