package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire;

import java.util.Objects;

public record ObservationCapture(String agentInstanceId,
                                 long sequence,
                                 long startedAtEpochMilli,
                                 long completedAtEpochMilli) {

    public ObservationCapture {
        Objects.requireNonNull(agentInstanceId, "agentInstanceId");
        requirePositive(sequence, "sequence");
        requirePositive(startedAtEpochMilli, "startedAtEpochMilli");
        requirePositive(completedAtEpochMilli, "completedAtEpochMilli");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " of an observation capture must be positive: " + value);
        }
    }
}
