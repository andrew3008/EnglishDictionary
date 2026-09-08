package space.br1440.platform.tracing.autoconfigure.reactive;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.client.WebClient;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration;
import space.br1440.platform.tracing.webflux.ReactiveCorrelationOperations;

@AutoConfiguration
@AutoConfigureAfter({TracingRuntimeAutoConfiguration.class, PlatformOutboundPropagationAutoConfiguration.class})
@ConditionalOnClass({WebFluxConfigurer.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.ENABLED, havingValue = "true", matchIfMissing = true)
public class ReactiveTracingAutoConfiguration {

    @Bean
    public ReactiveCorrelationOperations reactiveCorrelationOperations(RequestIdentityBoundarySupport boundarySupport) {
        return new DefaultReactiveCorrelationOperations(boundarySupport);
    }

    @Bean
    ReactiveIdentityContextPropagation reactiveIdentityContextPropagation(RequestIdentityBoundarySupport boundarySupport) {
        return new ReactiveIdentityContextPropagation(boundarySupport);
    }

    @Bean
    @ConditionalOnMissingBean(TraceResponseHeaderWebFilter.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 50)
    public TraceResponseHeaderWebFilter platformTraceResponseHeaderWebFilter(TraceOperations traceOperations,
                                                                             TracingProperties properties,
                                                                             InboundTraceControlExtractor traceControlExtractor) {
        return new TraceResponseHeaderWebFilter(traceOperations, properties, traceControlExtractor);
    }

    @Bean
    @org.springframework.context.annotation.Primary
    @ConditionalOnClass(org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention.class)
    @ConditionalOnMissingBean(PlatformReactiveServerRequestObservationConvention.class)
    public PlatformReactiveServerRequestObservationConvention platformReactiveServerRequestObservationConvention() {
        return new PlatformReactiveServerRequestObservationConvention();
    }

    @Bean
    @ConditionalOnClass(org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention.class)
    @ConditionalOnMissingBean(PlatformReactiveClientRequestObservationConvention.class)
    public PlatformReactiveClientRequestObservationConvention platformReactiveClientRequestObservationConvention() {
        return new PlatformReactiveClientRequestObservationConvention();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = TracingProperties.PREFIX + ".propagation.outbound", name = "enabled", havingValue = "true")
    public PlatformOutboundExchangeFilterFunction platformOutboundExchangeFilterFunction(@Qualifier("platformHttpOutboundPolicy") OutboundPropagationPolicy policy,
                                                                                         PlatformOutboundPropagation propagation) {
        return new PlatformOutboundExchangeFilterFunction(policy, propagation);
    }

    @Bean
    @ConditionalOnClass({WebClientCustomizer.class, WebClient.class})
    @ConditionalOnProperty(prefix = TracingProperties.PREFIX + ".propagation.outbound", name = "enabled", havingValue = "true")
    public WebClientCustomizer platformOutboundWebClientCustomizer(PlatformOutboundExchangeFilterFunction filter) {
        return builder -> builder.filters(filters -> {
            boolean alreadyPresent = filters.stream()
                    .anyMatch(f -> f instanceof PlatformOutboundExchangeFilterFunction);

            if (!alreadyPresent) {
                filters.add(filter);
            }
        });
    }
}
