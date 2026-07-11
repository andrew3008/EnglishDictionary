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
import space.br1440.platform.tracing.core.exception.ExceptionMessagePolicy;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.policy.SemconvMetrics;
import space.br1440.platform.tracing.core.enrichment.SpanEnricher;

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
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
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
    @ConditionalOnMissingBean
    public SpanEnricher platformSpanEnricher(AttributePolicy attributePolicy) {
        return new SpanEnricher(attributePolicy);
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
                    "platform.tracing.semantic.validation-mode=STRICT предназначен только для test/CI; "
                            + "в production runtime рекомендуется WARN (см. ADR-semconv-validation-modes)");
            case DISABLED -> log.warn(
                    "platform.tracing.semantic.validation-mode=DISABLED: runtime semantic-валидация отключена "
                            + "(reason='{}'). Это аварийный opt-out, НЕ режим миграции. PII-scrubbing продолжает "
                            + "работать независимо от режима.",
                    disabledReason == null || disabledReason.isBlank() ? "<не указана>" : disabledReason);
            case WARN -> {
                // production default — отдельный сигнал не требуется
            }
        }
    }
}
