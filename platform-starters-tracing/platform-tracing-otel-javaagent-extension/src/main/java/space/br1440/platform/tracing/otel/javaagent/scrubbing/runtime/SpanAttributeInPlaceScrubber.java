package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.IpPrefixTruncator;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeOutcome;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ScrubbingKeyNormalizer;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

final class SpanAttributeInPlaceScrubber {

    private static final String MASK = "***";
    private static final long SENTINEL_LONG = 0L;
    private static final double SENTINEL_DOUBLE = 0.0d;
    private static final boolean SENTINEL_BOOLEAN = Boolean.FALSE;

    private final List<ExecutableRule> rules;
    private final ScrubbingCollapseState collapse;
    private final HmacSha256Digest digest;
    private final RateLimitedLogger log;
    private final ScrubbingActionCounters actions = new ScrubbingActionCounters();

    SpanAttributeInPlaceScrubber(List<ExecutableRule> rules,
                                 ScrubbingCollapseState collapse,
                                 HmacSha256Digest digest,
                                 RateLimitedLogger log) {
        this.rules = rules;
        this.collapse = collapse;
        this.digest = digest;
        this.log = log;
    }

    void scrub(ReadWriteSpan span) {
        Attributes attributes;
        try {
            attributes = span.toSpanData().getAttributes();
        } catch (Throwable t) {
            actions.recordFailure();
            collapse.markAttributesUnverified();
            logFailure(ScrubbingLogEvent.SCRUB_PROCESSOR_SNAPSHOT_UNAVAILABLE, t);
            return;
        }

        try {
            attributes.forEach((key, value) -> {
                if (boundaryOwned(span, key)) {
                    return;
                }

                String normalizedKey = ScrubbingKeyNormalizer.normalize(key.getKey());
                MergeOutcome outcome = MergeEngine.evaluateDetailed(rules, normalizedKey, value);
                if (outcome.skippedCircuitOpen() > 0L) {
                    actions.addSkippedCircuitOpen(outcome.skippedCircuitOpen());
                }

                if (outcome.nonCriticalFailures() > 0L) {
                    actions.addFailures(outcome.nonCriticalFailures());
                }

                ScrubbingDecision decision = outcome.decision();
                if (decision.action() == ScrubbingAction.KEEP) {
                    return;
                }
                apply(span, key, value, decision);
            });
        } catch (Throwable t) {
            actions.recordFailure();
            collapse.markAttributesUnverified();
            applyFailClosedSnapshot(span, attributes);
            logFailure(ScrubbingLogEvent.SCRUB_PROCESSOR_CALLBACK_FAILED, t);
        }
    }

    private void logFailure(AgentLogEventType eventType, Throwable failure) {
        PlatformThrowables.propagateIfFatal(failure);
        log.log(eventType, fields -> fields.cause(failure));
    }

    long droppedActions() {
        return actions.dropped.sum();
    }

    long hashedActions() {
        return actions.hashed.sum();
    }

    long maskedActions() {
        return actions.masked.sum();
    }

    long truncatedActions() {
        return actions.truncated.sum();
    }

    long failures() {
        return actions.failures.sum();
    }

    long hashMissingKey() {
        return actions.hashMissingKey.sum();
    }

    long skippedCircuitOpen() {
        return actions.skippedCircuitOpen.sum();
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
                applyFailClosed(span, key, key.getType());
            } catch (Throwable t) {
                actions.recordFailure();
                logFailure(ScrubbingLogEvent.SCRUB_PROCESSOR_COLLAPSE_FAILED, t);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void apply(ReadWriteSpan span, AttributeKey<?> key, Object value, ScrubbingDecision decision) {
        AttributeType type = key.getType();

        boolean failClosed = (decision.action() == ScrubbingAction.MASK)
                && ScrubbingDecision.SCRUBBING_FAILED_REASON.equals(decision.reason());

        if (failClosed) {
            actions.recordFailure();
            log.log(ScrubbingLogEvent.SCRUBBING_FAIL_CLOSED_MASK_APPLIED,
                    fields -> fields.putString(AgentLogField.ATTRIBUTE, key.getKey()));
            applyFailClosed(span, key, type);
            return;
        }

        switch (type) {
            case STRING -> {
                String result = transformString((String) value, decision);
                if (result != null) {
                    span.setAttribute((AttributeKey<String>) key, result);
                }
            }

            case STRING_ARRAY -> {
                List<String> in = (List<String>) value;
                List<String> out = new ArrayList<>(in.size());
                for (String element : in) {
                    String transformed = transformString(element, decision);
                    out.add(transformed != null ? transformed : element);
                }
                span.setAttribute((AttributeKey<List<String>>) key, out);
            }

            case LONG, DOUBLE, BOOLEAN, LONG_ARRAY, DOUBLE_ARRAY, BOOLEAN_ARRAY ->
                    applyNonString(span, key, value, decision, type);

            default -> applyFailClosed(span, key, type);
        }
    }

    private void applyFailClosed(ReadWriteSpan span, AttributeKey<?> key, AttributeType type) {
        if (writeFailClosedSentinel(span, key, type)) {
            actions.recordMasked();
        }
    }

    @SuppressWarnings("unchecked")
    private boolean writeFailClosedSentinel(ReadWriteSpan span, AttributeKey<?> key, AttributeType type) {
        switch (type) {
            case STRING -> span.setAttribute(
                    (AttributeKey<String>) key,
                    ScrubbingDecision.SCRUBBING_FAILED_REASON
            );

            case STRING_ARRAY -> span.setAttribute(
                    (AttributeKey<List<String>>) key,
                    List.of(ScrubbingDecision.SCRUBBING_FAILED_REASON)
            );

            case LONG -> span.setAttribute((AttributeKey<Long>) key, SENTINEL_LONG);
            case DOUBLE -> span.setAttribute((AttributeKey<Double>) key, SENTINEL_DOUBLE);
            case BOOLEAN -> span.setAttribute((AttributeKey<Boolean>) key, SENTINEL_BOOLEAN);
            case LONG_ARRAY -> span.setAttribute((AttributeKey<List<Long>>) key, List.of(SENTINEL_LONG));
            case DOUBLE_ARRAY -> span.setAttribute((AttributeKey<List<Double>>) key, List.of(SENTINEL_DOUBLE));
            case BOOLEAN_ARRAY -> span.setAttribute((AttributeKey<List<Boolean>>) key, List.of(SENTINEL_BOOLEAN));

            default -> {
                log.log(ScrubbingLogEvent.UNSUPPORTED_SPAN_ATTRIBUTE_TYPE,
                        fields -> fields.putString(AgentLogField.TYPE, type.name()));
                collapse.markAttributesUnverified();
                return false;
            }
        }

        return true;
    }

    private String transformString(String value, ScrubbingDecision decision) {
        if (value == null) {
            return null;
        }

        switch (decision.action()) {
            case MASK -> {
                actions.recordMasked();
                return MASK;
            }

            case DROP -> {
                actions.recordDropped();
                return "";
            }

            case HASH -> {
                if (digest == null) {
                    actions.recordHashMissingKey();
                    actions.recordMasked();
                    return MASK;
                }
                actions.recordHashed();
                return digest.hashHex(value);
            }

            case TRUNCATE -> {
                return transformTruncate(value, decision);
            }

            default -> {
                return null;
            }
        }
    }

    private String transformTruncate(String value, ScrubbingDecision decision) {
        actions.recordTruncated();
        if (BuiltInSpanAttributeScrubbingRules.IP_ADDRESS.configName().equals(decision.reason())) {
            String truncated = IpPrefixTruncator.truncate(value);
            if (truncated == null) {
                actions.recordFailure();
                log.log(ScrubbingLogEvent.IP_PREFIX_TRUNCATION_FALLBACK_TO_FULL_MASK,
                        fields -> fields.putString(AgentLogField.REASON, decision.reason()));
                return MASK;
            }
            return truncated;
        }

        int max = decision.maxLength();
        if (max >= 0 && value.length() > max) {
            return value.substring(0, max);
        }

        return null;
    }

    private void applyNonString(ReadWriteSpan span, AttributeKey<?> key, Object value,
                                ScrubbingDecision decision, AttributeType type) {
        switch (decision.action()) {
            case MASK, DROP, HASH -> {
                actions.recordDropped();
                writeSentinel(span, key, value, type);
            }

            case TRUNCATE -> {
                // KEEP-эквивалент для чисел/boolean
            }

            default -> {
                // KEEP сюда не попадает
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void writeSentinel(ReadWriteSpan span, AttributeKey<?> key, Object value, AttributeType type) {
        switch (type) {
            case LONG -> span.setAttribute((AttributeKey<Long>) key, SENTINEL_LONG);
            case DOUBLE -> span.setAttribute((AttributeKey<Double>) key, SENTINEL_DOUBLE);
            case BOOLEAN -> span.setAttribute((AttributeKey<Boolean>) key, SENTINEL_BOOLEAN);

            case LONG_ARRAY -> span.setAttribute(
                    (AttributeKey<List<Long>>) key,
                    Collections.nCopies(((List<?>) value).size(), SENTINEL_LONG)
            );

            case DOUBLE_ARRAY -> span.setAttribute(
                    (AttributeKey<List<Double>>) key,
                    Collections.nCopies(((List<?>) value).size(), SENTINEL_DOUBLE)
            );

            case BOOLEAN_ARRAY -> span.setAttribute(
                    (AttributeKey<List<Boolean>>) key,
                    Collections.nCopies(((List<?>) value).size(), SENTINEL_BOOLEAN)
            );

            default -> {
                // строковые типы здесь не обрабатываются
            }
        }
    }

    private static final class ScrubbingActionCounters {

        private final LongAdder dropped = new LongAdder();
        private final LongAdder hashed = new LongAdder();
        private final LongAdder masked = new LongAdder();
        private final LongAdder truncated = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder hashMissingKey = new LongAdder();
        private final LongAdder skippedCircuitOpen = new LongAdder();

        private void recordDropped() {
            dropped.increment();
        }

        private void recordHashed() {
            hashed.increment();
        }

        private void recordMasked() {
            masked.increment();
        }

        private void recordTruncated() {
            truncated.increment();
        }

        private void recordFailure() {
            failures.increment();
        }

        private void addFailures(long count) {
            failures.add(count);
        }

        private void recordHashMissingKey() {
            hashMissingKey.increment();
        }

        private void addSkippedCircuitOpen(long count) {
            skippedCircuitOpen.add(count);
        }
    }
}
