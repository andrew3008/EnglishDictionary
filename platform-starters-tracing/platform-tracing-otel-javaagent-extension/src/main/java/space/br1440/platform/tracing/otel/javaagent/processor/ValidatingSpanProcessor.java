package space.br1440.platform.tracing.otel.javaagent.processor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ValidationLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.ratelimit.MinIntervalRateLimiter;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;

/**
 * Валидирует обязательные span-specific атрибуты до завершающего enrichment.
 *
 * <p>Маркер {@code platform.validation.missing} фиксирует исходное нарушение producer-контракта
 * и сохраняется, даже если enrichment затем дополнил итоговый span значениями по умолчанию.
 *
 * <p>При уровне, отличном от {@code DISABLED}, каждый span без заранее заданного producer-ом
 * {@code platform.trace.type} экспортируется с этим маркером. Log event ограничен одним разом
 * в 60 с отдельно для WARNING и ERROR. Сам атрибут не ограничивается. Всплеск маркера на
 * span'ах автоинструментации — ожидаемый сигнал восстановленного контракта, а не инцидент.
 */
public final class ValidatingSpanProcessor implements PlatformEndingSpanProcessor, ProcessorDiagnosticIdentity {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(ValidatingSpanProcessor.class);

    private static final String DIAGNOSTIC_ID = JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_VALIDATION;

    @Override
    public String diagnosticId() {
        return DIAGNOSTIC_ID;
    }

    private static final AttributeKey<String> PLATFORM_TYPE_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE);
    private static final AttributeKey<String> PLATFORM_RESULT_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);
    private static final List<Map.Entry<AttributeKey<String>, String>> REQUIRED_ATTRIBUTES = List.of(
            Map.entry(PLATFORM_TYPE_KEY, PlatformAttributes.PLATFORM_TYPE),
            Map.entry(PLATFORM_RESULT_KEY, PlatformAttributes.PLATFORM_RESULT)
    );

    private static final AttributeKey<String> VALIDATION_MISSING_KEY = AttributeKey.stringKey("platform.validation.missing");
    static final Duration MISSING_ATTRIBUTES_DIAGNOSTIC_THROTTLE_INTERVAL = Duration.ofSeconds(60);

    private final MinIntervalRateLimiter warnDiagnosticLimiter;
    private final MinIntervalRateLimiter errorDiagnosticLimiter;
    private final LongAdder validationViolationsTotal = new LongAdder();

    private final TracingSettingsSource acceptedPolicy;

    public ValidatingSpanProcessor(TracingSettingsSource acceptedPolicy) {
        this(System::nanoTime, acceptedPolicy);
    }

    ValidatingSpanProcessor(LongSupplier nanoTime, TracingSettingsSource acceptedPolicy) {
        LongSupplier requiredNanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        long diagnosticIntervalNanos = MISSING_ATTRIBUTES_DIAGNOSTIC_THROTTLE_INTERVAL.toNanos();
        this.warnDiagnosticLimiter = new MinIntervalRateLimiter(diagnosticIntervalNanos, requiredNanoTime);
        this.errorDiagnosticLimiter = new MinIntervalRateLimiter(diagnosticIntervalNanos, requiredNanoTime);
        this.acceptedPolicy = Objects.requireNonNull(acceptedPolicy, "acceptedPolicy");
    }

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnding(ReadWriteSpan span) {
        TracingSettingsRevision policy = acceptedPolicy.current();
        SpanAttributeValidationLevel level = policy.validationLevel();
        if (level.isDisabled()) {
            return;
        }

        List<String> missingNames = REQUIRED_ATTRIBUTES.stream()
                .filter(e -> span.getAttribute(e.getKey()) == null)
                .map(Map.Entry::getValue)
                .toList();

        if (!missingNames.isEmpty()) {
            String missing = String.join(",", missingNames);
            span.setAttribute(VALIDATION_MISSING_KEY, missing);
            validationViolationsTotal.increment();
            recordDiagnostic(level, missingNames.size(), missing);
        }
    }

    private void recordDiagnostic(SpanAttributeValidationLevel level, int missingCount, String missingKeys) {
        if (level.isError()) {
            if (errorDiagnosticLimiter.tryAcquire()) {
                logMissing(ValidationLogEvent.REQUIRED_SPAN_ATTRIBUTES_MISSING_ERROR, missingCount, missingKeys);
            }
        } else if (warnDiagnosticLimiter.tryAcquire()) {
            logMissing(ValidationLogEvent.REQUIRED_SPAN_ATTRIBUTES_MISSING_WARN, missingCount, missingKeys);
        }
    }

    private static void logMissing(ValidationLogEvent event, int missingCount, String missingKeys) {
        log.log(event, fields -> {
            fields.putLong(AgentLogField.COUNT, missingCount);
            fields.putString(AgentLogField.MISSING_KEYS, missingKeys);
        });
    }

    @Override
    public boolean isOnEndingRequired() {
        return true;
    }

    @Override
    public void onEnd(@Nonnull ReadableSpan span) {
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    public SpanAttributeValidationLevel validationLevel() {
        return acceptedPolicy.current().validationLevel();
    }

    public long getPolicyVersion() {
        return acceptedPolicy.current().acceptedSequence();
    }

    public long getValidationViolationsTotal() {
        return validationViolationsTotal.sum();
    }
}
