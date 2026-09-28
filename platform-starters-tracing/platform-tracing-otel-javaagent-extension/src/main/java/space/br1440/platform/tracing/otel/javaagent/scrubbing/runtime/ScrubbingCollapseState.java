package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;

final class ScrubbingCollapseState {

    private final PlatformAgentLogger log;
    private final AtomicBoolean attributesVerified = new AtomicBoolean(true);
    private final LongAdder collapseModeActivations = new LongAdder();
    private final LongAdder collapsedSpans = new LongAdder();

    ScrubbingCollapseState(PlatformAgentLogger log) {
        this.log = log;
    }

    boolean attributesVerified() {
        return attributesVerified.get();
    }

    void markAttributesUnverified() {
        if (attributesVerified.compareAndSet(true, false)) {
            collapseModeActivations.increment();
            log.log(ScrubbingLogEvent.SCRUBBING_BOUNDARY_COLLAPSED);
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
