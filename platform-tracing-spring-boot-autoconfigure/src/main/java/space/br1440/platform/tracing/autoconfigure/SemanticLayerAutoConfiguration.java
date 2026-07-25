package space.br1440.platform.tracing.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.api.span.enrich.SpanEnricher;
import space.br1440.platform.tracing.otel.span.enrich.DefaultSpanEnricher;
import space.br1440.platform.tracing.otel.exception.ExceptionMessagePolicy;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.otel.semconv.policy.SemconvMetrics;

/**
 * Авто-конфигурация платформенного semantic-слоя (Фаза 13): бин {@link AttributePolicy}.
 * <p>
 * Режим берётся из {@code platform.tracing.semantic.validation-mode} (prod default — WARN).
 * Метрики ({@link SemconvMetrics}) подключаются, только если в контексте есть Micrometer-реализация
 * (бин из {@link TracingMetricsAutoConfiguration}); иначе используется {@link SemconvMetrics#NOOP}
 * — core не зависит от Micrometer.
 * <p>
 * На старте логируются диагностические сигналы (см. {@code ADR-semconv-validation-modes}):
 * STRICT в runtime — unsupported (WARN), DISABLED — one-time WARN c причиной.
 */
@AutoConfiguration
@ConditionalOnClass(OpenTelemetry.class)
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "mode", havingValue = "AGENT", matchIfMissing = true)
@EnableConfigurationProperties(TracingProperties.class)
public class SemanticLayerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SemanticLayerAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AttributePolicy platformAttributePolicy(TracingProperties properties,
                                                   ObjectProvider<SemconvMetrics> metricsProvider,
                                                   ObjectProvider<SemconvValidationMode> modeOverrideProvider) {
        TracingProperties.Semantic semantic = properties.getSemantic();
        // Бин SemconvValidationMode (если есть) имеет приоритет над property — это канал для
        // SemconvStrictTestAutoConfiguration (platform-tracing-test): STRICT в test/CI.
        SemconvValidationMode mode = modeOverrideProvider.getIfAvailable(semantic::getValidationMode);
        SemconvMetrics metrics = metricsProvider.getIfAvailable(() -> SemconvMetrics.NOOP);

        emitStartupSignals(mode, semantic.getDisabledReason());
        return new AttributePolicy(mode, semantic.isAllowUnsafeAttributes(), metrics);
    }

    /** Enricher активного (Агентского) span'а — основной agent-first путь обогащения. */
    @Bean
    @ConditionalOnMissingBean(SpanEnricher.class)
    public SpanEnricher platformSpanEnricher() {
        return new DefaultSpanEnricher();
    }

    /** Политика публикации текста исключения (секьюр-дефолт: message/stacktrace off). */
    @Bean
    @ConditionalOnMissingBean
    public ExceptionMessagePolicy platformExceptionMessagePolicy(TracingProperties properties) {
        TracingProperties.Semantic.Exception ex = properties.getSemantic().getException();
        return new ExceptionMessagePolicy(ex.isIncludeMessage(), ex.isIncludeStacktrace());
    }

    /** Стандартизованная запись ошибки в активный span (sanitized exception-event). */
    @Bean
    @ConditionalOnMissingBean
    public ExceptionRecorder platformExceptionRecorder(ExceptionMessagePolicy messagePolicy) {
        return new ExceptionRecorder(messagePolicy);
    }

    private void emitStartupSignals(SemconvValidationMode mode, String disabledReason) {
        switch (mode) {
            case STRICT -> log.warn(
                    "platform.tracing.semantic.validation-mode=STRICT is intended for test/CI only; "
                            + "WARN is recommended for production runtime (see ADR-semconv-validation-modes)");
            case DISABLED -> log.warn(
                    "platform.tracing.semantic.validation-mode=DISABLED: runtime semantic validation is off "
                            + "(reason='{}'). This is an emergency opt-out, NOT a migration mode. PII scrubbing "
                            + "continues independently of the validation mode.",
                    disabledReason == null || disabledReason.isBlank() ? "<unspecified>" : disabledReason);
            case WARN -> {
                // production default — отдельный сигнал не требуется
            }
        }
    }
}
