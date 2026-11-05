package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.DiagnosticTextMaskingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeOutcome;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ScrubbingKeyNormalizer;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ScrubbingValueTransformer;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ScrubbingValueTransformer.Result;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

final class SpanAttributeInPlaceScrubber {

    private final List<ExecutableRule> rules;
    private final ScrubbingCollapseState collapse;
    private final RateLimitedLogger log;
    private final ScrubbingRuntime runtime;
    private final LongAdder dropped = new LongAdder();
    private final LongAdder hashed = new LongAdder();
    private final LongAdder masked = new LongAdder();
    private final LongAdder truncated = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder hashMissingKey = new LongAdder();
    private final LongAdder skippedCircuitOpen = new LongAdder();

    SpanAttributeInPlaceScrubber(List<ExecutableRule> rules,
                                 ScrubbingCollapseState collapse,
                                 RateLimitedLogger log,
                                 ScrubbingRuntime runtime) {
        this.rules = rules;
        this.collapse = collapse;
        this.log = log;
        this.runtime = runtime;
    }

    void scrub(ReadWriteSpan span) {
        Attributes attributes;
        try {
            attributes = span.getAttributes();
        } catch (Throwable t) {
            failures.increment();
            collapse.markAttributesUnverified();
            logFailure(ScrubbingLogEvent.SCRUB_PROCESSOR_SNAPSHOT_UNAVAILABLE, t);
            return;
        }

        try {
            attributes.forEach((key, value) -> scrubAttribute(span, key, value));
        } catch (Throwable t) {
            failures.increment();
            collapse.markAttributesUnverified();
            applyFailClosedSnapshot(span, attributes);
            logFailure(ScrubbingLogEvent.SCRUB_PROCESSOR_CALLBACK_FAILED, t);
        }
    }

    private void scrubAttribute(ReadWriteSpan span, AttributeKey<?> key, Object value) {
        if (boundaryOwned(span, key)) {
            return;
        }

        String normalizedKey = ScrubbingKeyNormalizer.normalize(key.getKey());
        if (!runtime.diagnosticAttributePermitted(normalizedKey, value)) {
            dropDiagnosticAttribute(span, key);
            return;
        }

        MergeOutcome outcome = MergeEngine.evaluateDetailed(rules, normalizedKey, value, key.getType());
        if (outcome.skippedCircuitOpen() > 0L) {
            skippedCircuitOpen.add(outcome.skippedCircuitOpen());
        }
        long ruleFailures = outcome.nonCriticalFailures() + outcome.criticalFailures();
        if (ruleFailures > 0L) {
            failures.add(ruleFailures);
        }

        ScrubbingDecision decision = outcome.decision();
        if (decision.action() == ScrubbingAction.DROP
                && (ScrubbingRuntime.isDiagnosticAttribute(normalizedKey) || isDiagnosticDrop(decision))) {
            dropDiagnosticAttribute(span, key);
            return;
        }

        if (decision.action() == ScrubbingAction.MASK
                && ScrubbingDecision.SCRUBBING_FAILED_REASON.equals(decision.reason())) {
            if (outcome.criticalFailures() == 0L) {
                failures.increment();
            }
            log.log(ScrubbingLogEvent.SCRUBBING_FAIL_CLOSED_MASK_APPLIED,
                    fields -> fields.putString(AgentLogField.ATTRIBUTE, key.getKey()));
            applyFailClosed(span, key);
            return;
        }

        Result result = runtime.transformAttribute(key.getType(), value, outcome);
        if (result.action() == ScrubbingAction.KEEP) {
            return;
        }

        Object replacement = result.action() == ScrubbingAction.DROP
                ? ScrubbingValueTransformer.dropValue(key.getType(), value)
                : result.value();
        if (!runtime.diagnosticAttributePermitted(normalizedKey, replacement)) {
            dropDiagnosticAttribute(span, key);
            return;
        }

        write(span, key, replacement);
        recordApplied(result);
    }

    private void recordApplied(Result result) {
        switch (result.action()) {
            case DROP -> dropped.increment();
            case MASK -> masked.increment();
            case HASH -> hashed.increment();
            case TRUNCATE -> truncated.increment();
            case KEEP -> { }
        }
        switch (result.fallback()) {
            case MISSING_HMAC_KEY -> hashMissingKey.increment();
            case INVALID_IP -> {
                failures.increment();
                log.log(ScrubbingLogEvent.IP_PREFIX_TRUNCATION_FALLBACK_TO_FULL_MASK,
                        fields -> fields.putString(AgentLogField.REASON, ScrubbingValueTransformer.IP_ADDRESS_REASON));
            }
            case MALFORMED_INPUT -> failures.increment();
            default -> { }
        }
    }

    private void dropDiagnosticAttribute(ReadWriteSpan span, AttributeKey<?> key) {
        if (!"".equals(span.getAttribute(AttributeKey.stringKey(key.getKey())))) {
            span.setAttribute(AttributeKey.stringKey(key.getKey()), "");
            dropped.increment();
        }
    }

    private void logFailure(AgentLogEventType eventType, Throwable failure) {
        PlatformThrowables.propagateIfFatal(failure);
        log.log(eventType, fields -> fields.cause(failure));
    }

    long droppedActions() {
        return dropped.sum();
    }

    long hashedActions() {
        return hashed.sum();
    }

    long maskedActions() {
        return masked.sum();
    }

    long truncatedActions() {
        return truncated.sum();
    }

    long failures() {
        return failures.sum();
    }

    long hashMissingKey() {
        return hashMissingKey.sum();
    }

    long skippedCircuitOpen() {
        return skippedCircuitOpen.sum();
    }

    private static boolean boundaryOwned(ReadWriteSpan span, AttributeKey<?> snapshotKey) {
        if (snapshotKey.getType() == AttributeType.VALUE) {
            return true;
        }

        Object valueProjection = span.getAttribute(AttributeKey.valueKey(snapshotKey.getKey()));
        return (valueProjection != null) && (span.getAttribute(snapshotKey) == null);
    }

    private void applyFailClosedSnapshot(ReadWriteSpan span, Attributes attributes) {
        attributes.forEach((key, ignored) -> {
            if (boundaryOwned(span, key)) {
                return;
            }

            try {
                applyFailClosed(span, key);
            } catch (Throwable t) {
                failures.increment();
                logFailure(ScrubbingLogEvent.SCRUB_PROCESSOR_COLLAPSE_FAILED, t);
            }
        });
    }

    private void applyFailClosed(ReadWriteSpan span, AttributeKey<?> key) {
        if (ScrubbingRuntime.isDiagnosticAttribute(ScrubbingKeyNormalizer.normalize(key.getKey()))) {
            dropDiagnosticAttribute(span, key);
        } else {
            write(span, key, ScrubbingValueTransformer.failClosedValue(key.getType()));
            masked.increment();
        }
    }

    private static boolean isDiagnosticDrop(ScrubbingDecision decision) {
        return DiagnosticTextMaskingRule.NAME.equals(decision.reason())
                || ScrubbingDecision.SCRUBBING_FAILED_REASON.equals(decision.reason());
    }

    @SuppressWarnings("unchecked")
    private static void write(ReadWriteSpan span, AttributeKey<?> key, Object value) {
        span.setAttribute((AttributeKey<Object>) key, value);
    }
}
