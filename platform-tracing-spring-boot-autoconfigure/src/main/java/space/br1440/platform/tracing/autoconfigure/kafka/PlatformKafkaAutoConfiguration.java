package space.br1440.platform.tracing.autoconfigure.kafka;

import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.tracing.api.TraceOperations;

@AutoConfiguration
@ConditionalOnClass(name = {
        "org.springframework.kafka.annotation.KafkaListener",
        "org.apache.kafka.clients.consumer.ConsumerRecord"
})
@ConditionalOnBean(TraceOperations.class)
@ConditionalOnProperty(prefix = "platform.tracing.kafka", name = "batch-links-enabled", havingValue = "true", matchIfMissing = false)
public class PlatformKafkaAutoConfiguration {

    @Bean
    @ConditionalOnBean(OpenTelemetry.class)
    public KafkaBatchLinksAspect kafkaBatchLinksAspect(OpenTelemetry openTelemetry,
                                                       TraceOperations traceOperations) {
        return new KafkaBatchLinksAspect(openTelemetry, traceOperations);
    }
}
