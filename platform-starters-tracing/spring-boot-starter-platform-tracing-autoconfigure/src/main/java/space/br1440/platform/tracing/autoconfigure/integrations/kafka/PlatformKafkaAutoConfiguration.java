package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.enrich.SpanEnricher;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;

@AutoConfiguration(after = TracingRuntimeAutoConfiguration.class)
@ConditionalOnClass(name = {
        "org.springframework.kafka.annotation.KafkaListener",
        "org.apache.kafka.clients.consumer.ConsumerRecord"
})
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.ENABLED, havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(TraceOperations.class)
public class PlatformKafkaAutoConfiguration {

    @Bean
    public KafkaRequestIdentityAspect kafkaRequestIdentityAspect(RequestIdentityBoundarySupport identityBoundary,
                                                                 SpanEnricher spanEnricher) {
        return new KafkaRequestIdentityAspect(identityBoundary, spanEnricher);
    }
}
