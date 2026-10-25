package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule;

import io.opentelemetry.sdk.trace.ReadWriteSpan;

public final class ScrubbingRuntime implements AutoCloseable {

    private static final RateLimitedLogger log = new RateLimitedLogger(ScrubbingRuntime.class);
    private static final PlatformAgentLogger lifecycleLog = PlatformAgentLogger.get(ScrubbingRuntime.class);

    private final boolean enabled;
    private final ScrubbingPolicy policy;
    private final boolean hasHmacKey;
    private final HmacSha256Digest digest;
    private final ScrubbingCollapseState collapseState = new ScrubbingCollapseState(lifecycleLog);
    private final ScrubbingBoundaryCounters boundaryCounters = new ScrubbingBoundaryCounters();
    private final ScrubbingInstallationBarrier installation = new ScrubbingInstallationBarrier();
    private final URLClassLoader extensionClassLoader;
    private final Object extensionClassLoaderCloseMonitor = new Object();
    private boolean extensionClassLoaderClosed;
    private RuntimeException extensionClassLoaderCloseFailure;
    private final SpanAttributeInPlaceScrubber attributeScrubber;

    ScrubbingRuntime(boolean enabled,
                     List<RuntimeRule> rules,
                     long policyVersion,
                     MaskedSecret hmacSecret,
                     boolean failFast,
                     URLClassLoader extensionClassLoader) {
        this.enabled = enabled;
        this.policy = ScrubbingPolicy.create(rules, policyVersion);
        this.extensionClassLoader = extensionClassLoader;

        this.hasHmacKey = hmacSecret != null && hmacSecret.isPresent();
        if (!hasHmacKey && failFast) {
            String message = """
                    Scrubbing HMAC key is not set and missing-key-policy=fail_fast: \
                    set platform.tracing.scrubbing.hmac-key""";

            throw new SanitizerBootstrapException(
                    SanitizerBootstrapException.HMAC_INITIALIZATION_FAILED,
                    message
            );
        }

        if (enabled && !hasHmacKey) {
            lifecycleLog.log(ScrubbingLogEvent.SCRUBBING_HMAC_KEY_MISSING);
        }

        if (hasHmacKey) {
            byte[] ownedKey = hmacSecret.copyBytes();
            try {
                this.digest = HmacSha256Digest.from(ownedKey);
            } catch (IllegalStateException e) {
                throw new SanitizerBootstrapException(
                        SanitizerBootstrapException.HMAC_INITIALIZATION_FAILED,
                        "Failed to initialize HMAC-SHA256",
                        e
                );
            }
        } else {
            this.digest = null;
        }

        this.attributeScrubber = new SpanAttributeInPlaceScrubber(
                policy.attributeExecutableRules(), collapseState, digest, log);
    }

    public static ScrubbingRuntime disabled() {
        return new ScrubbingRuntime(false, List.of(), 0L, null, false, null);
    }

    public boolean enabled() {
        return enabled;
    }

    public List<ExecutableRule> attributeExecutableRules() {
        return policy.attributeExecutableRules();
    }

    public List<ExecutableRule> builtInTextRules() {
        return policy.builtInTextRules();
    }

    public long policyVersion() {
        return policy.version();
    }

    public long ruleCount() {
        return policy.attributeExecutableRules().size();
    }

    public long openBreakerCount() {
        return policy.attributeExecutableRules().stream().filter(rule -> rule.getBreaker().isOpen()).count();
    }

    public long totalBreakerFailures() {
        return policy.attributeExecutableRules().stream().mapToLong(rule -> rule.getBreaker().getTotalFailures()).sum();
    }

    public boolean hasHmacKey() {
        return hasHmacKey;
    }

    public String hmacHex(String value) {
        return digest.hashHex(value);
    }

    public void scrubSpanAttributes(ReadWriteSpan span) {
        if (!enabled) {
            return;
        }

        if (policy.attributeExecutableRules().isEmpty()) {
            return;
        }

        attributeScrubber.scrub(span);
    }

    public long droppedActions() {
        return attributeScrubber.droppedActions();
    }

    public long hashedActions() {
        return attributeScrubber.hashedActions();
    }

    public long maskedActions() {
        return attributeScrubber.maskedActions();
    }

    public long truncatedActions() {
        return attributeScrubber.truncatedActions();
    }

    public long failures() {
        return attributeScrubber.failures();
    }

    public long hashMissingKey() {
        return attributeScrubber.hashMissingKey();
    }

    public long skippedCircuitOpen() {
        return attributeScrubber.skippedCircuitOpen();
    }

    public boolean attributesVerified() {
        return collapseState.attributesVerified();
    }

    public void markAttributesUnverified() {
        collapseState.markAttributesUnverified();
    }

    public void recordCollapsedSpan() {
        collapseState.recordCollapsedSpan();
    }

    public long collapseModeActivations() {
        return collapseState.collapseModeActivations();
    }

    public long collapsedSpans() {
        return collapseState.collapsedSpans();
    }

    public ScrubbingBoundaryMetrics boundaryMetrics() {
        return boundaryCounters.snapshot();
    }

    public void recordBoundaryValueAttributesRemoved(long removed) {
        boundaryCounters.addValueAttributesRemoved(removed);
    }

    public void recordBoundaryInputSpan() {
        boundaryCounters.recordInputSpan();
    }

    public void recordBoundaryOutputSpan() {
        boundaryCounters.recordOutputSpan();
    }

    public void recordBoundarySpanChanged() {
        boundaryCounters.recordSpanChanged();
    }

    public void recordBoundarySpanDroppedFailClosed() {
        boundaryCounters.recordSpanDroppedFailClosed();
    }

    public void recordBoundarySpansDroppedFailClosed(long count) {
        boundaryCounters.addSpansDroppedFailClosed(count);
    }

    public void recordBoundaryEventScrubbed() {
        boundaryCounters.recordEventScrubbed();
    }

    public void recordBoundaryLinkScrubbed() {
        boundaryCounters.recordLinkScrubbed();
    }

    public void recordBoundarySpanNameScrubbed() {
        boundaryCounters.recordSpanNameScrubbed();
    }

    public void recordBoundaryExceptionEventCanonicalized() {
        boundaryCounters.recordExceptionEventCanonicalized();
    }

    public void recordBoundaryCollapseFailure() {
        boundaryCounters.recordCollapseFailure();
    }

    public void recordBoundaryExporterInvocation() {
        boundaryCounters.recordExporterInvocation();
    }

    public void recordBoundaryHashDowngradedToMask() {
        boundaryCounters.recordHashDowngradedToMask();
    }

    public void recordBoundaryTruncateDowngradedToMask() {
        boundaryCounters.recordTruncateDowngradedToMask();
    }

    public void recordBoundaryCriticalFailures(long count) {
        boundaryCounters.addCriticalFailures(count);
    }

    public void recordBoundaryNonCriticalFailures(long count) {
        boundaryCounters.addNonCriticalFailures(count);
    }

    public void recordBoundarySkippedCircuitOpen(long count) {
        boundaryCounters.addSkippedCircuitOpen(count);
    }

    public void recordBoundarySurfaceCollapsedFailClosed() {
        boundaryCounters.recordSurfaceCollapsedFailClosed();
    }

    public void recordBoundaryBatchPreparationFailure() {
        boundaryCounters.recordBatchPreparationFailure();
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

    static ScrubbingRuntime create(List<RuntimeRule> rules, long policyVersion,
                                   MaskedSecret hmacSecret, boolean failFast) {
        return create(rules, policyVersion, hmacSecret, failFast, null);
    }

    public static ScrubbingRuntime create(List<RuntimeRule> rules,
                                          long policyVersion,
                                          MaskedSecret hmacSecret,
                                          boolean failFast,
                                          URLClassLoader extensionClassLoader) {
        Objects.requireNonNull(rules, "rules");
        try {
            return new ScrubbingRuntime(
                    true,
                    rules,
                    policyVersion,
                    hmacSecret,
                    failFast,
                    extensionClassLoader
            );
        } catch (RuntimeException | Error failure) {
            closeAfterConstructionFailure(extensionClassLoader, failure);
            throw failure;
        }
    }

    @Override
    public void close() {
        if (extensionClassLoader == null) {
            return;
        }

        synchronized (extensionClassLoaderCloseMonitor) {
            if (extensionClassLoaderClosed) {
                if (extensionClassLoaderCloseFailure != null) {
                    throw extensionClassLoaderCloseFailure;
                }

                return;
            }

            extensionClassLoaderClosed = true;
            try {
                extensionClassLoader.close();
            } catch (IOException failure) {
                lifecycleLog.log(ScrubbingLogEvent.EXTENSION_CLASSLOADER_CLOSE_FAILED,
                        fields -> fields.cause(failure));
                extensionClassLoaderCloseFailure = new UncheckedIOException("Failed to close scrubbing extension classloader", failure);
                throw extensionClassLoaderCloseFailure;
            }
        }
    }

    private static void closeAfterConstructionFailure(URLClassLoader classLoader,
                                                      Throwable constructionFailure) {
        if (classLoader == null) {
            return;
        }

        try {
            classLoader.close();
        } catch (IOException closeFailure) {
            constructionFailure.addSuppressed(closeFailure);
            lifecycleLog.log(ScrubbingLogEvent.EXTENSION_CLASSLOADER_CLOSE_FAILED,
                    fields -> fields.cause(closeFailure));
        }
    }

    private static final class ScrubbingBoundaryCounters {

        private final LongAdder inputSpans = new LongAdder();
        private final LongAdder outputSpans = new LongAdder();
        private final LongAdder spansChanged = new LongAdder();
        private final LongAdder spansDroppedFailClosed = new LongAdder();
        private final LongAdder eventsScrubbed = new LongAdder();
        private final LongAdder linksScrubbed = new LongAdder();
        private final LongAdder spanNamesScrubbed = new LongAdder();
        private final LongAdder exceptionEventsCanonicalized = new LongAdder();
        private final LongAdder collapseFailures = new LongAdder();
        private final LongAdder exporterInvocations = new LongAdder();
        private final LongAdder hashDowngradedToMask = new LongAdder();
        private final LongAdder truncateDowngradedToMask = new LongAdder();
        private final LongAdder criticalFailures = new LongAdder();
        private final LongAdder nonCriticalFailures = new LongAdder();
        private final LongAdder skippedCircuitOpen = new LongAdder();
        private final LongAdder surfacesCollapsedFailClosed = new LongAdder();
        private final LongAdder batchPreparationFailures = new LongAdder();
        private final LongAdder valueAttributesRemoved = new LongAdder();

        private ScrubbingBoundaryMetrics snapshot() {
            return new ScrubbingBoundaryMetrics(
                    inputSpans.sum(),
                    outputSpans.sum(),
                    spansChanged.sum(),
                    spansDroppedFailClosed.sum(),
                    eventsScrubbed.sum(),
                    linksScrubbed.sum(),
                    spanNamesScrubbed.sum(),
                    exceptionEventsCanonicalized.sum(),
                    collapseFailures.sum(),
                    exporterInvocations.sum(),
                    hashDowngradedToMask.sum(),
                    truncateDowngradedToMask.sum(),
                    criticalFailures.sum(),
                    nonCriticalFailures.sum(),
                    skippedCircuitOpen.sum(),
                    surfacesCollapsedFailClosed.sum(),
                    batchPreparationFailures.sum(),
                    valueAttributesRemoved.sum()
            );
        }

        private void addValueAttributesRemoved(long removed) {
            valueAttributesRemoved.add(requireNonNegative(removed, "removed"));
        }

        private void recordInputSpan() {
            inputSpans.increment();
        }

        private void recordOutputSpan() {
            outputSpans.increment();
        }

        private void recordSpanChanged() {
            spansChanged.increment();
        }

        private void recordSpanDroppedFailClosed() {
            spansDroppedFailClosed.increment();
        }

        private void addSpansDroppedFailClosed(long count) {
            spansDroppedFailClosed.add(requireNonNegative(count, "count"));
        }

        private void recordEventScrubbed() {
            eventsScrubbed.increment();
        }

        private void recordLinkScrubbed() {
            linksScrubbed.increment();
        }

        private void recordSpanNameScrubbed() {
            spanNamesScrubbed.increment();
        }

        private void recordExceptionEventCanonicalized() {
            exceptionEventsCanonicalized.increment();
        }

        private void recordCollapseFailure() {
            collapseFailures.increment();
        }

        private void recordExporterInvocation() {
            exporterInvocations.increment();
        }

        private void recordHashDowngradedToMask() {
            hashDowngradedToMask.increment();
        }

        private void recordTruncateDowngradedToMask() {
            truncateDowngradedToMask.increment();
        }

        private void addCriticalFailures(long count) {
            criticalFailures.add(requireNonNegative(count, "count"));
        }

        private void addNonCriticalFailures(long count) {
            nonCriticalFailures.add(requireNonNegative(count, "count"));
        }

        private void addSkippedCircuitOpen(long count) {
            skippedCircuitOpen.add(requireNonNegative(count, "count"));
        }

        private void recordSurfaceCollapsedFailClosed() {
            surfacesCollapsedFailClosed.increment();
        }

        private void recordBatchPreparationFailure() {
            batchPreparationFailures.increment();
        }

        private static long requireNonNegative(long count, String name) {
            if (count < 0L) {
                throw new IllegalArgumentException(name + " must not be negative: " + count);
            }
            return count;
        }
    }

    private static final class ScrubbingInstallationBarrier {

        private final AtomicBoolean processorInstalled = new AtomicBoolean();
        private final AtomicBoolean exportModeResolved = new AtomicBoolean();
        private final AtomicInteger wrappedExporterCount = new AtomicInteger();
        private final AtomicBoolean exportPathProtected = new AtomicBoolean();

        private ScrubbingInstallationBarrier() {
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
}
