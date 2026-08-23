package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.resilience.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.IpPrefixTruncator;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeOutcome;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

public final class ScrubbingRuntime {

    private static final RateLimitedLogger log = new RateLimitedLogger(ScrubbingRuntime.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    static final String MASK = "***";
    private static final long SENTINEL_LONG = 0L;
    private static final double SENTINEL_DOUBLE = 0.0d;
    private static final boolean SENTINEL_BOOLEAN = Boolean.FALSE;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final boolean enabled;
    private final List<ExecutableRule> attributeExecutableRules;
    private final List<ExecutableRule> builtInTextRules;
    private final long policyVersion;
    private final String policySource;

    private final byte[] hmacKey;
    private final boolean hasHmacKey;
    private final ThreadLocal<Mac> threadLocalMac;

    private final LongAdder droppedActions = new LongAdder();
    private final LongAdder hashedActions = new LongAdder();
    private final LongAdder maskedActions = new LongAdder();
    private final LongAdder truncatedActions = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder hashMissingKey = new LongAdder();

    private final MonotonicCounter boundaryInputSpans = new MonotonicCounter();
    private final MonotonicCounter boundaryOutputSpans = new MonotonicCounter();
    private final MonotonicCounter boundarySpansChanged = new MonotonicCounter();
    private final MonotonicCounter boundarySpansDroppedFailClosed = new MonotonicCounter();
    private final MonotonicCounter boundaryEventsScrubbed = new MonotonicCounter();
    private final MonotonicCounter boundaryLinksScrubbed = new MonotonicCounter();
    private final MonotonicCounter boundarySpanNamesScrubbed = new MonotonicCounter();
    private final MonotonicCounter boundaryExceptionEventsCanonicalized = new MonotonicCounter();
    private final MonotonicCounter boundaryCollapseFailures = new MonotonicCounter();
    private final MonotonicCounter boundaryExporterInvocations = new MonotonicCounter();
    private final MonotonicCounter boundaryHashDowngradedToMask = new MonotonicCounter();
    private final MonotonicCounter boundaryTruncateDowngradedToMask = new MonotonicCounter();
    private final MonotonicCounter boundaryCriticalFailures = new MonotonicCounter();
    private final MonotonicCounter boundaryNonCriticalFailures = new MonotonicCounter();
    private final MonotonicCounter boundarySkippedCircuitOpen = new MonotonicCounter();
    private final MonotonicCounter boundarySurfacesCollapsedFailClosed = new MonotonicCounter();
    private final MonotonicCounter boundaryBatchPreparationFailures = new MonotonicCounter();
    private final MonotonicCounter boundaryValueAttributesRemoved = new MonotonicCounter();

    private final AtomicBoolean attributesVerified = new AtomicBoolean(true);
    private final LongAdder collapseModeActivations = new LongAdder();
    private final LongAdder collapsedSpans = new LongAdder();

    private final Installation installation = new Installation();

    ScrubbingRuntime(boolean enabled,
                     List<RuntimeRule> rules,
                     long policyVersion,
                     String policySource,
                     MaskedSecret hmacSecret,
                     boolean failFast) {
        this.enabled = enabled;
        this.attributeExecutableRules = rules.stream().map(RuntimeRule::executable).toList();
        this.builtInTextRules = rules.stream()
                .filter(r -> r.origin() == RuleOrigin.BUILT_IN)
                .map(RuntimeRule::executable)
                .toList();
        this.policyVersion = policyVersion;
        this.policySource = policySource;

        this.hasHmacKey = hmacSecret != null && hmacSecret.isPresent();
        if (!hasHmacKey && failFast) {
            throw new SanitizerBootstrapException(SanitizerBootstrapException.HMAC_INITIALIZATION_FAILED,
                    "Scrubbing HMAC key is not set and missing-key-policy=fail-fast: "
                            + "set platform.tracing.scrubbing.hmac-key");
        }

        if (enabled && !hasHmacKey) {
            log.warn(AgentEventCodes.SCRUB_HMAC_MISSING, "Scrubbing HMAC key is not set");
        }

        this.hmacKey = hasHmacKey ? hmacSecret.copyBytes() : null;
        this.threadLocalMac = hasHmacKey ? ThreadLocal.withInitial(this::newMac) : null;
        if (hasHmacKey) {
            try {
                newMac();
            } catch (IllegalStateException e) {
                throw new SanitizerBootstrapException(SanitizerBootstrapException.HMAC_INITIALIZATION_FAILED,
                        "Failed to initialize HMAC-SHA256", e);
            }
        }
    }

    static ScrubbingRuntime disabled() {
        return new ScrubbingRuntime(false, List.of(), 0L, "disabled", null, false);
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(hmacKey, HMAC_ALGORITHM));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize HMAC-SHA256", e);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public List<ExecutableRule> attributeExecutableRules() {
        return attributeExecutableRules;
    }

    public List<ExecutableRule> builtInTextRules() {
        return builtInTextRules;
    }

    public long policyVersion() {
        return policyVersion;
    }

    public String policySource() {
        return policySource;
    }

    public long ruleCount() {
        return attributeExecutableRules.size();
    }

    public long openBreakerCount() {
        return attributeExecutableRules.stream().filter(w -> w.getBreaker().isOpen()).count();
    }

    public long totalBreakerFailures() {
        return attributeExecutableRules.stream().mapToLong(w -> w.getBreaker().getTotalFailures()).sum();
    }

    public boolean hasHmacKey() {
        return hasHmacKey;
    }

    public String hmacHex(String value) {
        Mac mac = threadLocalMac.get();
        byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        char[] hex = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int b = digest[i] & 0xFF;
            hex[i * 2] = HEX[b >>> 4];
            hex[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(hex);
    }

    public void scrubSpanAttributes(ReadWriteSpan span) {
        if (!enabled) {
            return;
        }

        List<ExecutableRule> wrappers = attributeExecutableRules;
        if (wrappers.isEmpty()) {
            return;
        }

        Attributes attributes;
        try {
            attributes = span.toSpanData().getAttributes();
        } catch (Throwable t) {
            failures.increment();
            markAttributesUnverified();
            ScrubbingFailureLog.error(log, "processor.snapshot_unavailable", t);
            return;
        }

        try {
            attributes.forEach((key, value) -> {
                if (boundaryOwned(key)) {
                    return;
                }

                String normalizedKey = ScrubbingKeyNormalizer.normalize(key.getKey());
                MergeOutcome outcome = MergeEngine.evaluateDetailed(wrappers, normalizedKey, value);
                if (outcome.nonCriticalFailures() > 0L) {
                    failures.add(outcome.nonCriticalFailures());
                }
                ScrubbingDecision decision = outcome.decision();
                if (decision.action() == ScrubbingAction.KEEP) {
                    return;
                }
                apply(span, key, value, decision);
            });
        } catch (Throwable t) {
            failures.increment();
            markAttributesUnverified();
            applyFailClosedSnapshot(span, attributes);
            ScrubbingFailureLog.error(log, "processor.callback_failed", t);
        }
    }

    private static boolean boundaryOwned(AttributeKey<?> key) {
        return (key.getType() == AttributeType.VALUE);
    }

    private void applyFailClosedSnapshot(ReadWriteSpan span, Attributes attributes) {
        attributes.forEach((key, ignored) -> {
            if (boundaryOwned(key)) {
                return;
            }

            try {
                applyFailClosed(span, key, key.getType());
            } catch (Throwable t) {
                failures.increment();
                ScrubbingFailureLog.error(log, "processor.collapse_failed", t);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void apply(ReadWriteSpan span, AttributeKey<?> key, Object value, ScrubbingDecision decision) {
        AttributeType type = key.getType();

        boolean failClosed = (decision.action() == ScrubbingAction.MASK)
                && ScrubbingDecision.SCRUBBING_FAILED_REASON.equals(decision.reason());
        if (failClosed) {
            failures.increment();
            log.warn(AgentEventCodes.SCRUB_RULE_FAILED, "Applying fail-closed mask",
                    "attribute", key.getKey());
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
            maskedActions.increment();
        }
    }

    @SuppressWarnings("unchecked")
    private boolean writeFailClosedSentinel(ReadWriteSpan span, AttributeKey<?> key, AttributeType type) {
        switch (type) {
            case STRING -> span.setAttribute((AttributeKey<String>) key, ScrubbingDecision.SCRUBBING_FAILED_REASON);
            case STRING_ARRAY -> span.setAttribute((AttributeKey<List<String>>) key, List.of(ScrubbingDecision.SCRUBBING_FAILED_REASON));
            case LONG -> span.setAttribute((AttributeKey<Long>) key, SENTINEL_LONG);
            case DOUBLE -> span.setAttribute((AttributeKey<Double>) key, SENTINEL_DOUBLE);
            case BOOLEAN -> span.setAttribute((AttributeKey<Boolean>) key, SENTINEL_BOOLEAN);
            case LONG_ARRAY -> span.setAttribute((AttributeKey<List<Long>>) key, List.of(SENTINEL_LONG));
            case DOUBLE_ARRAY -> span.setAttribute((AttributeKey<List<Double>>) key, List.of(SENTINEL_DOUBLE));
            case BOOLEAN_ARRAY -> span.setAttribute((AttributeKey<List<Boolean>>) key, List.of(SENTINEL_BOOLEAN));

            default -> {
                log.warn(AgentEventCodes.SCRUB_UNSUPPORTED_TYPE, "Unsupported attribute type",
                        "type", String.valueOf(type));
                markAttributesUnverified();
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
                maskedActions.increment();
                return MASK;
            }

            case DROP -> {
                droppedActions.increment();
                return "";
            }

            case HASH -> {
                if (!hasHmacKey) {
                    hashMissingKey.increment();
                    maskedActions.increment();
                    return MASK;
                }
                hashedActions.increment();
                return hmacHex(value);
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
        truncatedActions.increment();
        if (BuiltInSpanAttributeScrubbingRules.IP_ADDRESS.configName().equals(decision.reason())) {
            String truncated = IpPrefixTruncator.truncate(value);
            if (truncated == null) {
                failures.increment();
                log.warn(AgentEventCodes.SCRUB_RULE_FAILED, "IP prefix truncation failed; falling back to full mask",
                        "reason", decision.reason());
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
                droppedActions.increment();
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
            case LONG_ARRAY -> span.setAttribute((AttributeKey<List<Long>>) key, Collections.nCopies(((List<?>) value).size(), SENTINEL_LONG));
            case DOUBLE_ARRAY -> span.setAttribute((AttributeKey<List<Double>>) key, Collections.nCopies(((List<?>) value).size(), SENTINEL_DOUBLE));
            case BOOLEAN_ARRAY -> span.setAttribute((AttributeKey<List<Boolean>>) key, Collections.nCopies(((List<?>) value).size(), SENTINEL_BOOLEAN));
            default -> {
                // строковые типы здесь не обрабатываются
            }
        }
    }

    public long droppedActions() {
        return droppedActions.sum();
    }

    public long hashedActions() {
        return hashedActions.sum();
    }

    public long maskedActions() {
        return maskedActions.sum();
    }

    public long truncatedActions() {
        return truncatedActions.sum();
    }

    public long failures() {
        return failures.sum();
    }

    public long hashMissingKey() {
        return hashMissingKey.sum();
    }

    public boolean attributesVerified() {
        return attributesVerified.get();
    }

    public void markAttributesUnverified() {
        if (attributesVerified.compareAndSet(true, false)) {
            collapseModeActivations.increment();
            log.error(AgentEventCodes.SCRUB_BOUNDARY_COLLAPSE, "Scrubbing boundary collapsed");
        }
    }

    public void recordCollapsedSpan() {
        collapsedSpans.increment();
    }

    public long collapseModeActivations() {
        return collapseModeActivations.sum();
    }

    public long collapsedSpans() {
        return collapsedSpans.sum();
    }

    public ScrubbingBoundaryMetrics boundaryMetrics() {
        return new ScrubbingBoundaryMetrics(
                boundaryInputSpans.sum(),
                boundaryOutputSpans.sum(),
                boundarySpansChanged.sum(),
                boundarySpansDroppedFailClosed.sum(),
                boundaryEventsScrubbed.sum(),
                boundaryLinksScrubbed.sum(),
                boundarySpanNamesScrubbed.sum(),
                boundaryExceptionEventsCanonicalized.sum(),
                boundaryCollapseFailures.sum(),
                boundaryExporterInvocations.sum(),
                boundaryHashDowngradedToMask.sum(),
                boundaryTruncateDowngradedToMask.sum(),
                boundaryCriticalFailures.sum(),
                boundaryNonCriticalFailures.sum(),
                boundarySkippedCircuitOpen.sum(),
                boundarySurfacesCollapsedFailClosed.sum(),
                boundaryBatchPreparationFailures.sum(),
                boundaryValueAttributesRemoved.sum()
        );
    }

    public void recordBoundaryValueAttributesRemoved(long removed) {
        boundaryValueAttributesRemoved.add(removed);
    }

    public void recordBoundaryInputSpan() {
        boundaryInputSpans.increment();
    }

    public void recordBoundaryOutputSpan() {
        boundaryOutputSpans.increment();
    }

    public void recordBoundarySpanChanged() {
        boundarySpansChanged.increment();
    }

    public void recordBoundarySpanDroppedFailClosed() {
        boundarySpansDroppedFailClosed.increment();
    }

    public void recordBoundarySpansDroppedFailClosed(long count) {
        boundarySpansDroppedFailClosed.add(count);
    }

    public void recordBoundaryEventScrubbed() {
        boundaryEventsScrubbed.increment();
    }

    public void recordBoundaryLinkScrubbed() {
        boundaryLinksScrubbed.increment();
    }

    public void recordBoundarySpanNameScrubbed() {
        boundarySpanNamesScrubbed.increment();
    }

    public void recordBoundaryExceptionEventCanonicalized() {
        boundaryExceptionEventsCanonicalized.increment();
    }

    public void recordBoundaryCollapseFailure() {
        boundaryCollapseFailures.increment();
    }

    public void recordBoundaryExporterInvocation() {
        boundaryExporterInvocations.increment();
    }

    public void recordBoundaryHashDowngradedToMask() {
        boundaryHashDowngradedToMask.increment();
    }

    public void recordBoundaryTruncateDowngradedToMask() {
        boundaryTruncateDowngradedToMask.increment();
    }

    public void recordBoundaryCriticalFailures(long count) {
        boundaryCriticalFailures.add(count);
    }

    public void recordBoundaryNonCriticalFailures(long count) {
        boundaryNonCriticalFailures.add(count);
    }

    public void recordBoundarySkippedCircuitOpen(long count) {
        boundarySkippedCircuitOpen.add(count);
    }

    public void recordBoundarySurfaceCollapsedFailClosed() {
        boundarySurfacesCollapsedFailClosed.increment();
    }

    public void recordBoundaryBatchPreparationFailure() {
        boundaryBatchPreparationFailures.increment();
    }

    public void acknowledgeProcessorInstalled() {
        installation.acknowledgeProcessorInstalled();
    }

    public void acknowledgeExportModeResolved() {
        installation.acknowledgeExportModeResolved();
    }

    public void acknowledgeExporterWrapped() {
        installation.acknowledgeExporterWrapped();
    }

    public void acknowledgeExportPathProtected() {
        installation.acknowledgeExportPathProtected();
    }

    public void requireInstallationComplete() {
        installation.requireComplete();
    }

    private static final class Installation {

        private final AtomicBoolean processorInstalled = new AtomicBoolean();
        private final AtomicBoolean exportModeResolved = new AtomicBoolean();
        private final AtomicInteger wrappedExporterCount = new AtomicInteger();
        private final AtomicBoolean exportPathProtected = new AtomicBoolean();

        private Installation() {
        }

        private void acknowledgeProcessorInstalled() {
            processorInstalled.set(true);
        }

        private void acknowledgeExportModeResolved() {
            exportModeResolved.set(true);
        }

        private void acknowledgeExporterWrapped() {
            wrappedExporterCount.incrementAndGet();
        }

        private void acknowledgeExportPathProtected() {
            exportPathProtected.set(true);
        }

        private boolean isComplete() {
            return processorInstalled.get()
                    && exportModeResolved.get()
                    && wrappedExporterCount.get() >= 1
                    && exportPathProtected.get();
        }

        private void requireComplete() {
            if (!isComplete()) {
                throw new IllegalStateException("Scrubbing installation barrier incomplete: " + describe());
            }
        }

        private String describe() {
            return "processorInstalled=" + processorInstalled.get()
                    + ", exportModeResolved=" + exportModeResolved.get()
                    + ", wrappedExporterCount=" + wrappedExporterCount.get()
                    + ", exportPathProtected=" + exportPathProtected.get();
        }
    }

    static ScrubbingRuntime create(List<RuntimeRule> rules, long policyVersion, String policySource,
                                   MaskedSecret hmacSecret, boolean failFast) {
        Objects.requireNonNull(rules, "rules");
        return new ScrubbingRuntime(true, rules, policyVersion, policySource, hmacSecret, failFast);
    }
}
