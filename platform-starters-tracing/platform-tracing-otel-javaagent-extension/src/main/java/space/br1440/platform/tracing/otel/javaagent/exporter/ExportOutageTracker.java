package space.br1440.platform.tracing.otel.javaagent.exporter;

final class ExportOutageTracker {

    private final Object lock = new Object();

    private long issuedAttemptSequence;
    private long outageFailures;
    private long lastFailureSequence;
    private long lastSuccessSequence;

    ExportOutageTracker() {
    }

    ExportOutageTracker(long initialAttemptSequence) {
        this.issuedAttemptSequence = initialAttemptSequence;
    }

    long startAttempt() {
        synchronized (lock) {
            return ++issuedAttemptSequence;
        }
    }

    void recordFailure(long attemptSequence) {
        synchronized (lock) {
            if (!isAfter(attemptSequence, lastSuccessSequence)) {
                return;
            }

            if (outageFailures < Long.MAX_VALUE) {
                outageFailures++;
            }

            if (isAfter(attemptSequence, lastFailureSequence)) {
                lastFailureSequence = attemptSequence;
            }
        }
    }

    long recordSuccess(long attemptSequence) {
        synchronized (lock) {
            if (isAfter(attemptSequence, lastSuccessSequence)) {
                lastSuccessSequence = attemptSequence;
            }

            if (outageFailures == 0L || !isAfter(attemptSequence, lastFailureSequence)) {
                return 0L;
            }

            long failures = outageFailures;
            outageFailures = 0L;
            return failures;
        }
    }

    private static boolean isAfter(long candidate, long fence) {
        return Long.compareUnsigned(candidate, fence) > 0;
    }
}
