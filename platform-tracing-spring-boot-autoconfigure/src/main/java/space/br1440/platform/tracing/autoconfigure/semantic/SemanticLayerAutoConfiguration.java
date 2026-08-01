package space.br1440.platform.tracing.autoconfigure.semantic;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.api.span.enrich.SpanEnricher;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.otel.exception.ExceptionMessagePolicy;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.otel.semconv.policy.SemconvMetrics;
import space.br1440.platform.tracing.otel.span.enrich.DefaultSpanEnricher;

import io.opentelemetry.api.OpenTelemetry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AutoConfiguration
@ConditionalOnClass(OpenTelemetry.class)
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.ENABLED, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TracingProperties.class)
public class SemanticLayerAutoConfiguration {

    private static final String UNSPECIFIED_REASON = "<unspecified>";

    @Bean
    @ConditionalOnMissingBean
    public AttributePolicy platformAttributePolicy(TracingProperties properties,
                                                   ObjectProvider<SemconvMetrics> metricsProvider,
                                                   ObjectProvider<SemconvValidationMode> modeOverrideProvider) {
        TracingProperties.Semantic semantic = properties.getSemantic();
        SemconvValidationMode mode = modeOverrideProvider.getIfAvailable(semantic::getValidationMode);
        SemconvMetrics metrics = metricsProvider.getIfAvailable(() -> SemconvMetrics.NOOP);

        emitStartupSignals(mode, semantic.getDisabledReason());
        return new AttributePolicy(mode, semantic.isAllowUnsafeAttributes(), metrics);
    }

    @Bean
    @ConditionalOnMissingBean(SpanEnricher.class)
    public SpanEnricher platformSpanEnricher() {
        return new DefaultSpanEnricher();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExceptionRecorder platformExceptionRecorder(TracingProperties properties) {
        TracingProperties.Semantic.ExceptionEvent exception = properties.getSemantic().getException();
        ExceptionMessagePolicy messagePolicy = new ExceptionMessagePolicy(
                exception.isIncludeMessage(),
                exception.isIncludeStacktrace()
        );

        return new ExceptionRecorder(messagePolicy);
    }

    private void emitStartupSignals(SemconvValidationMode mode, String disabledReason) {
        switch (mode) {
            case STRICT -> log.warn("""
                    platform.tracing.semantic.validation-mode=STRICT is intended for test/CI only; \
                    WARN is recommended for production runtime (see ADR-semconv-validation-modes)""");

            case DISABLED -> {
                String reason = (disabledReason == null || disabledReason.isBlank()) ? UNSPECIFIED_REASON : disabledReason;
                log.warn("""
                        platform.tracing.semantic.validation-mode=DISABLED: runtime semantic validation is off \
                        (reason='{}'). This is an emergency opt-out, NOT a migration mode. PII scrubbing \
                        continues independently of the validation mode.""", reason);
            }

            case WARN -> {
            }
        }
    }
}
