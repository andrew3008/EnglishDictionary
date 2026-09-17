package space.br1440.platform.tracing.otel.javaagent.processor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;

/**
 * Валидирует обязательные span-specific атрибуты до завершающего enrichment.
 *
 * <p>Маркер {@code platform.validation.missing} фиксирует исходное нарушение producer-контракта
 * и сохраняется, даже если enrichment затем дополнил итоговый span значениями по умолчанию.
 */
public final class ValidatingSpanProcessor implements PlatformEndingSpanProcessor, ProcessorDiagnosticIdentity {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(ValidatingSpanProcessor.class);

    private static final String DIAGNOSTIC_ID = "validation";

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

    private static final long NEVER_WARNED = (Long.MIN_VALUE / 2);

    private final AtomicLong lastLenientDiagnosticAt = new AtomicLong(NEVER_WARNED);
    private final AtomicLong lastStrictDiagnosticAt = new AtomicLong(NEVER_WARNED);
    private final LongAdder validationViolationsTotal = new LongAdder();
    private final LongSupplier clock;

    private final TracingSettingsSource acceptedPolicy;

    public ValidatingSpanProcessor(TracingSettingsSource acceptedPolicy) {
        this(System::currentTimeMillis, acceptedPolicy);
    }

    ValidatingSpanProcessor(LongSupplier clock, TracingSettingsSource acceptedPolicy) {
        this.clock = Objects.requireNonNull(clock, "clock");
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
        if (!policy.validationEnabled()) {
            return;
        }

        String missing = REQUIRED_ATTRIBUTES.stream()
                .filter(e -> span.getAttribute(e.getKey()) == null)
                .map(Map.Entry::getValue)
                .collect(Collectors.joining(","));

        if (!missing.isEmpty()) {
            span.setAttribute(VALIDATION_MISSING_KEY, missing);
            validationViolationsTotal.increment();
            recordDiagnostic(policy.validationStrict());
        }
    }

    private void recordDiagnostic(boolean strict) {
        long now = clock.getAsLong();
        AtomicLong lastDiagnosticAt = strict ? lastStrictDiagnosticAt : lastLenientDiagnosticAt;
        long previous = lastDiagnosticAt.get();
        long thresholdMs = MISSING_ATTRIBUTES_DIAGNOSTIC_THROTTLE_INTERVAL.toMillis();

        if ((now - previous) < thresholdMs || !lastDiagnosticAt.compareAndSet(previous, now)) {
            return;
        }

        if (strict) {
            log.error(AgentEventCodes.VALID_MISSING_ATTRIBUTES, "Required span attributes are missing");
        } else {
            log.warn(AgentEventCodes.VALID_MISSING_ATTRIBUTES, "Required span attributes are missing");
        }
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

    public boolean isEnabled() {
        return acceptedPolicy.current().validationEnabled();
    }

    public boolean isStrict() {
        return acceptedPolicy.current().validationStrict();
    }

    public long getPolicyVersion() {
        return acceptedPolicy.current().acceptedSequence();
    }

    public String getPolicySource() {
        return "accepted-state";
    }

    public long getValidationViolationsTotal() {
        return validationViolationsTotal.sum();
    }

    long lastDiagnosticTimestampForTesting(boolean strict) {
        AtomicLong lastDiagnosticAt = strict ? lastStrictDiagnosticAt : lastLenientDiagnosticAt;
        return lastDiagnosticAt.get();
    }
}
