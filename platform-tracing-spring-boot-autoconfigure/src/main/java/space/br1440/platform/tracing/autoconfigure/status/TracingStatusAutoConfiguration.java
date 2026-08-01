package space.br1440.platform.tracing.autoconfigure.status;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.control.policy.AcceptedTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationAuthorityAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.agent.TracingModeDiagnostics;

/**
 * Безусловно регистрирует единый status read-model для AGENT и DISABLED режимов.
 */
@AutoConfiguration
@AutoConfigureAfter({
        TracingConfigurationAuthorityAutoConfiguration.class,
        TracingRuntimeAutoConfiguration.class,
        TracingPolicyControlAutoConfiguration.class
})
@ConditionalOnClass(Endpoint.class)
public class TracingStatusAutoConfiguration {

    @Bean
    @ConditionalOnBean(TraceOperations.class)
    @ConditionalOnMissingBean
    public TracingStatusAssembler tracingStatusAssembler(
            TraceOperations traceOperations,
            TracingBootstrapSettings bootstrapSettings,
            PlatformTracingJmxClient jmxClient,
            ObjectProvider<AcceptedTracingPolicyReader> acceptedPolicyReader,
            TracingModeDiagnostics diagnostics) {
        return new TracingStatusAssembler(
                traceOperations,
                bootstrapSettings,
                jmxClient,
                acceptedPolicyReader.getIfAvailable(),
                diagnostics);
    }

    @Bean
    @ConditionalOnBean(TraceOperations.class)
    @ConditionalOnMissingBean
    public TracingStatusEndpoint tracingStatusEndpoint(TracingStatusAssembler assembler) {
        return new TracingStatusEndpoint(assembler);
    }
}
