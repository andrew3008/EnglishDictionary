package space.br1440.platform.tracing.autoconfigure.attributes;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidationMetrics;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;

import io.opentelemetry.api.OpenTelemetry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AutoConfiguration
@ConditionalOnClass(OpenTelemetry.class)
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.ENABLED, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TracingProperties.class)
public class SpanAttributeValidationAutoConfiguration {

    private static final String UNSPECIFIED_REASON = "<unspecified>";

    @Bean
    @ConditionalOnMissingBean
    public SpanAttributeValidator platformSpanAttributeValidator(TracingProperties properties,
                                                                 ObjectProvider<SpanAttributeValidationMetrics> metricsProvider) {
        TracingProperties.SpanAttributes.Validation validation = properties.getSpanAttributes().getValidation();
        SpanAttributeValidationLevel level = validation.getLevel();
        SpanAttributeValidationMetrics metrics = metricsProvider.getIfAvailable(() -> SpanAttributeValidationMetrics.NOOP);

        emitStartupSignals(level, validation.getDisabledReason());
        return new SpanAttributeValidator(level, metrics);
    }

    private void emitStartupSignals(SpanAttributeValidationLevel level, String disabledReason) {
        switch (level) {
            case ERROR -> log.warn("""
                    platform.tracing.span-attributes.validation.level=ERROR is intended for test/CI only; \
                    WARNING is recommended for production runtime. Application ERROR fails before startSpan(); \
                    agent ERROR remains diagnostic and does not drop the span""");

            case DISABLED -> {
                String reason = StringUtils.hasText(disabledReason) ? disabledReason : UNSPECIFIED_REASON;
                log.warn("""
                        platform.tracing.span-attributes.validation.level=DISABLED: runtime span-attribute \
                        validation is off (reason='{}'). This is an emergency opt-out, NOT a migration mode. \
                        PII scrubbing continues independently of the validation level.""", reason);
            }

            case WARNING -> {
            }
        }
    }
}
