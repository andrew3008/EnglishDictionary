package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import java.time.Instant;

public record JmxPointReadState(long lastAppliedSequence,
                                long lastSuccessSequence,
                                Instant lastAttemptAt,
                                Instant lastSuccessAt) {

    public static final JmxPointReadState NEVER_READ = new JmxPointReadState(-1L, -1L, null, null);

}
