package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import java.time.Instant;

public record JmxDiagnosticSnapshot(JmxContractPoint point,
                                    JmxDiagnosticCategory category,
                                    JmxDiagnosticKind kind,
                                    long lifetimeCount,
                                    long suppressedWarnCount,
                                    Instant firstSeenAt,
                                    Instant lastSeenAt,
                                    Instant lastAttemptAt,
                                    boolean unrecovered) {
}
