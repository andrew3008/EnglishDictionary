package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

public record MergeOutcome(ScrubbingDecision decision,
                           @Nullable String maskedValue,
                           long skippedCircuitOpen,
                           long nonCriticalFailures,
                           long criticalFailures,
                           boolean requiresBoundaryCollapse) {
}
