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
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration;
import space.br1440.platform.tracing.otel.propagation.control.DefaultOutboundPropagationPolicy;
import space.br1440.platform.tracing.otel.propagation.control.TrustedDestinationMatchers;

/**
 * Авто-конфигурация исходящей инжекции платформенных заголовков в Kafka producer.
 * <p>
 * Активна при {@code platform.tracing.kafka.propagate-platform-headers=true}. При активации
 * policy enabled (как HTTP {@code propagation.outbound.enabled}); фактический inject режется
 * {@code trusted-topic-patterns}. Span'ы Kafka создаёт OTel Java Agent — платформа их не дублирует.
 */
@AutoConfiguration
@AutoConfigureAfter(PlatformOutboundPropagationAutoConfiguration.class)
@ConditionalOnClass(name = {
        "org.springframework.kafka.core.DefaultKafkaProducerFactory",
        "org.apache.kafka.clients.producer.ProducerInterceptor"
})
@ConditionalOnProperty(prefix = "platform.tracing.kafka", name = "propagate-platform-headers", havingValue = "true")
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
                outbound.isPropagateRequestId()
        );

        return new PlatformKafkaProducerFactoryCustomizer(policy, propagation, identityBoundary);
    }
}
