package space.br1440.platform.tracing.autoconfigure.kafka;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.TraceControlHeaderInjector;
import space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

/**
 * Авто-конфигурация исходящей инжекции платформенных заголовков в Kafka producer (agent-compatible).
 * <p>
 * Активна при {@code platform.tracing.kafka.propagate-platform-headers=true}. Режим
 * {@code platform.tracing.kafka.mode=disabled} приводит к {@code DENY_ALL} (интерсептор остаётся,
 * но ничего не инжектит). Span'ы Kafka создаёт OTel Java Agent — платформа их не дублирует.
 */
@AutoConfiguration
@ConditionalOnClass(name = {
        "org.springframework.kafka.core.DefaultKafkaProducerFactory",
        "org.apache.kafka.clients.producer.ProducerInterceptor"
})
@ConditionalOnProperty(prefix = "platform.tracing.kafka", name = "propagate-platform-headers", havingValue = "true")
public class PlatformKafkaOutboundAutoConfiguration {

    @Bean
    @ConditionalOnBean(TraceControlHeaderInjector.class)
    @ConditionalOnMissingBean
    public PlatformKafkaProducerFactoryCustomizer platformKafkaProducerFactoryCustomizer(
            TracingProperties properties, TraceControlHeaderInjector injector) {
        TracingProperties.Kafka kafka = properties.getKafka();
        TracingProperties.Propagation.Outbound outbound = properties.getPropagation().getOutbound();

        // enabled для Kafka = режим agent-compatible (disabled -> DENY_ALL).
        boolean enabled = "agent-compatible".equalsIgnoreCase(kafka.getMode());
        TrustedDestinationMatcher topicMatcher = TrustedDestinationMatcher.forKafkaTopics(kafka.getTrustedTopicPatterns());
        OutboundPropagationPolicy policy = new OutboundPropagationPolicy(
                enabled,
                topicMatcher,
                outbound.isPropagateForceTrace(),
                outbound.isPropagateQaTrace(),
                outbound.isPropagateRequestId());

        return new PlatformKafkaProducerFactoryCustomizer(policy, injector);
    }
}
