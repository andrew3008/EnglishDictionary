package space.br1440.platform.tracing.autoconfigure.status;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxReadAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

@AutoConfiguration
@AutoConfigureAfter({
        TracingControlChannelAutoConfiguration.class,
        TracingRuntimeAutoConfiguration.class,
        PlatformJmxReadAutoConfiguration.class,
        PlatformJmxControlAutoConfiguration.class,
        TracingSettingsControlAutoConfiguration.class
})
@ConditionalOnClass(Endpoint.class)
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
public class TracingStatusAutoConfiguration {

    @Bean
    @ConditionalOnBean(TraceOperations.class)
    @ConditionalOnMissingBean
    public TracingStatusAssembler tracingStatusAssembler(TraceOperations traceOperations,
                                                         TracingRuntime tracingRuntime,
                                                         TracingBootstrapSettings bootstrapSettings,
                                                         PlatformTracingJmxClient jmxClient,
                                                         ObjectProvider<AcceptedSettingsStatusReader> acceptedSettingsReader,
                                                         TracingDeploymentDiagnostics diagnostics,
                                                         AgentReadinessCoherenceGuard coherenceGuard) {
        return new TracingStatusAssembler(
                traceOperations,
                tracingRuntime,
                bootstrapSettings,
                jmxClient,
                acceptedSettingsReader.getIfAvailable(() -> Optional::empty),
                diagnostics,
                coherenceGuard
        );
    }

    @Bean
    @ConditionalOnBean(TraceOperations.class)
    @ConditionalOnMissingBean
    public TracingStatusEndpoint tracingStatusEndpoint(TracingStatusAssembler assembler) {
        return new TracingStatusEndpoint(assembler);
    }
}
