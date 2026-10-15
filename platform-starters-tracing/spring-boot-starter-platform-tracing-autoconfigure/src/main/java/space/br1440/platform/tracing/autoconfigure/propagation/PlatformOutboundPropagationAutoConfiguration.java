package space.br1440.platform.tracing.autoconfigure.propagation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultInboundTraceControlExtractor;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultOutboundPropagationPolicy;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultTraceControlHeaderInjector;
import space.br1440.platform.tracing.runtime.propagation.control.TrustedDestinationMatchers;

@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.ENABLED, havingValue = "true", matchIfMissing = true)
public class PlatformOutboundPropagationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InboundTraceControlExtractor inboundTraceControlExtractor() {
        return new DefaultInboundTraceControlExtractor();
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformOutboundPropagation platformOutboundPropagation(TraceOperations traceOperations) {
        return new DefaultTraceControlHeaderInjector(new TraceOperationsIdentitySource(traceOperations));
    }

    @Bean(name = "platformHttpOutboundPolicy")
    @ConditionalOnMissingBean(name = "platformHttpOutboundPolicy")
    public OutboundPropagationPolicy platformHttpOutboundPolicy(TracingProperties properties) {
        TracingProperties.Propagation.Outbound outbound = properties.getPropagation().getOutbound();
        TrustedDestinationMatcher matcher = TrustedDestinationMatchers.forHttpHosts(
                outbound.getTrustedHostPatterns(),
                outbound.isAllowIpLiterals()
        );

        return new DefaultOutboundPropagationPolicy(
                outbound.isEnabled(),
                matcher,
                outbound.isPropagateForceTrace(),
                outbound.isPropagateQaTrace(),
                outbound.isPropagateRequestId(),
                outbound.isPropagateCorrelationId()
        );
    }
}
