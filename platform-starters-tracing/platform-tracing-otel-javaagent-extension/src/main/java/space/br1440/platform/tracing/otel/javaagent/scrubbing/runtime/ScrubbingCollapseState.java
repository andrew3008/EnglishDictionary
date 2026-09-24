package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.resilience.RateLimitedLogger;

final class ScrubbingCollapseState {

    private final RateLimitedLogger log;
    private final AtomicBoolean attributesVerified = new AtomicBoolean(true);
    private final LongAdder collapseModeActivations = new LongAdder();
    private final LongAdder collapsedSpans = new LongAdder();

    ScrubbingCollapseState(RateLimitedLogger log) {
        this.log = log;
    }

    boolean attributesVerified() {
        return attributesVerified.get();
    }

    void markAttributesUnverified() {
        if (attributesVerified.compareAndSet(true, false)) {
            collapseModeActivations.increment();
            log.error(AgentEventCode.SCRUB_BOUNDARY_COLLAPSE, "Scrubbing boundary collapsed");
        }
    }

    void recordCollapsedSpan() {
        collapsedSpans.increment();
    }

    long collapseModeActivations() {
        return collapseModeActivations.sum();
    }

    long collapsedSpans() {
        return collapsedSpans.sum();
    }
}
