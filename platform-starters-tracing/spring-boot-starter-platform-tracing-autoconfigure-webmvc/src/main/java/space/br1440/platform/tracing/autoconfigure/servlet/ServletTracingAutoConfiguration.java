package space.br1440.platform.tracing.autoconfigure.servlet;

import jakarta.servlet.Servlet;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestClient;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration;

@AutoConfiguration
@AutoConfigureAfter({TracingRuntimeAutoConfiguration.class, PlatformOutboundPropagationAutoConfiguration.class})
@ConditionalOnClass({Servlet.class, FilterRegistrationBean.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.ENABLED, havingValue = "true", matchIfMissing = true)
public class ServletTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "platformTraceResponseHeaderServletFilterRegistration")
    public FilterRegistrationBean<TraceResponseHeaderServletFilter> platformTraceResponseHeaderServletFilterRegistration(
            TraceOperations traceOperations,
            TracingProperties properties,
            RequestIdentityBoundarySupport identityBoundary,
            InboundTraceControlExtractor traceControlExtractor) {
        TraceResponseHeaderServletFilter filter = new TraceResponseHeaderServletFilter(
                traceOperations,
                properties,
                identityBoundary,
                traceControlExtractor
        );
        FilterRegistrationBean<TraceResponseHeaderServletFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("platformTraceResponseHeaderServletFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        return registration;
    }

    @Bean
    @ConditionalOnClass(org.springframework.http.server.observation.DefaultServerRequestObservationConvention.class)
    @ConditionalOnMissingBean(PlatformServerRequestObservationConvention.class)
    public PlatformServerRequestObservationConvention platformServerRequestObservationConvention() {
        return new PlatformServerRequestObservationConvention();
    }

    @Bean
    @ConditionalOnClass(org.springframework.http.client.observation.DefaultClientRequestObservationConvention.class)
    @ConditionalOnMissingBean(PlatformClientRequestObservationConvention.class)
    public PlatformClientRequestObservationConvention platformClientRequestObservationConvention() {
        return new PlatformClientRequestObservationConvention();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = TracingProperties.PREFIX + ".propagation.outbound", name = "enabled", havingValue = "true")
    public PlatformOutboundHttpInterceptor platformOutboundHttpInterceptor(@Qualifier("platformHttpOutboundPolicy") OutboundPropagationPolicy policy,
                                                                           PlatformOutboundPropagation propagation,
                                                                           RequestIdentityBoundarySupport identityBoundary) {
        return new PlatformOutboundHttpInterceptor(policy, propagation, identityBoundary);
    }

    @Bean
    @ConditionalOnClass(RestTemplateCustomizer.class)
    @ConditionalOnProperty(prefix = TracingProperties.PREFIX + ".propagation.outbound", name = "enabled", havingValue = "true")
    public RestTemplateCustomizer platformOutboundRestTemplateCustomizer(PlatformOutboundHttpInterceptor interceptor) {
        return restTemplate -> {
            boolean alreadyPresent = restTemplate.getInterceptors().stream()
                    .anyMatch(i -> i instanceof PlatformOutboundHttpInterceptor);

            if (!alreadyPresent) {
                restTemplate.getInterceptors().add(interceptor);
            }
        };
    }

    @Bean
    @ConditionalOnClass({RestClientCustomizer.class, RestClient.class})
    @ConditionalOnProperty(prefix = TracingProperties.PREFIX + ".propagation.outbound", name = "enabled", havingValue = "true")
    public RestClientCustomizer platformOutboundRestClientCustomizer(PlatformOutboundHttpInterceptor interceptor) {
        return builder -> builder.requestInterceptor(interceptor);
    }
}
