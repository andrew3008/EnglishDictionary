package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultOutboundPropagationPolicy;
import space.br1440.platform.tracing.runtime.propagation.control.TrustedDestinationMatchers;

@AutoConfiguration
@AutoConfigureAfter(PlatformOutboundPropagationAutoConfiguration.class)
@ConditionalOnClass(name = {
        "org.springframework.kafka.core.DefaultKafkaProducerFactory",
        "org.apache.kafka.clients.producer.ProducerInterceptor"
})
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.KAFKA_PROPAGATE_PLATFORM_HEADERS, havingValue = "true")
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.ENABLED, havingValue = "true", matchIfMissing = true)
public class PlatformKafkaOutboundAutoConfiguration {

    @Bean
    @ConditionalOnBean(PlatformOutboundPropagation.class)
    @ConditionalOnMissingBean
    public PlatformKafkaProducerFactoryCustomizer platformKafkaProducerFactoryCustomizer(TracingProperties properties,
                                                                                         PlatformOutboundPropagation propagation,
                                                                                         RequestIdentityBoundarySupport identityBoundary) {
        TracingProperties.Kafka kafka = properties.getKafka();
        TracingProperties.Propagation.Outbound outbound = properties.getPropagation().getOutbound();

        TrustedDestinationMatcher topicMatcher = TrustedDestinationMatchers.forKafkaTopics(kafka.getTrustedTopicPatterns());
        OutboundPropagationPolicy policy = new DefaultOutboundPropagationPolicy(
                true,
                topicMatcher,
                outbound.isPropagateForceTrace(),
                outbound.isPropagateQaTrace(),
                outbound.isPropagateRequestId(),
                outbound.isPropagateCorrelationId()
        );

        return new PlatformKafkaProducerFactoryCustomizer(policy, propagation, identityBoundary);
    }
}
