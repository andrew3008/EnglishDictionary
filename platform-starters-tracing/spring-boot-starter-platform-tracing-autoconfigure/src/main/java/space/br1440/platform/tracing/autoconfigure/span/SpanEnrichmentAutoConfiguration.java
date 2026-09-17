package space.br1440.platform.tracing.autoconfigure.span;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.span.enrich.SpanEnricher;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.otel.span.enrich.DefaultSpanEnricher;

import io.opentelemetry.api.OpenTelemetry;

@AutoConfiguration
@ConditionalOnClass(OpenTelemetry.class)
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.ENABLED, havingValue = "true", matchIfMissing = true)
public class SpanEnrichmentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SpanEnricher.class)
    public SpanEnricher platformSpanEnricher() {
        return new DefaultSpanEnricher();
    }
}
