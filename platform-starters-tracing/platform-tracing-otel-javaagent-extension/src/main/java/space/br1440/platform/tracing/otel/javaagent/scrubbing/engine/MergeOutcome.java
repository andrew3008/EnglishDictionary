package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

public record MergeOutcome(ScrubbingDecision decision,
                           long skippedCircuitOpen,
                           long nonCriticalFailures,
                           long criticalFailures) {
}
