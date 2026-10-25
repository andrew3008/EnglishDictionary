package space.br1440.platform.tracing.autoconfigure.diagnostics;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;

@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
public class TracingRuntimeDiagnosticsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PlatformTracingJmxClient platformTracingJmxClient(AgentReadinessCoherenceGuard coherenceGuard) {
        return new PlatformTracingJmxClient(coherenceGuard);
    }
}
