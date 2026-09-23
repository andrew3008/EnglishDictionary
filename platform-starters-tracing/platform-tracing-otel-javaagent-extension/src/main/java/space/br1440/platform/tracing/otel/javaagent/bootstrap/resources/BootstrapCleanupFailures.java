package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class BootstrapCleanupFailures {

    public static Throwable rollback(BootstrapResources resources, Throwable primary) {
        try {
            resources.close();
            return null;
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primary) {
                primary.addSuppressed(cleanupFailure);
            }

            return cleanupFailure;
        }
    }

    public static Throwable aggregate(Throwable aggregate, Throwable failure) {
        if (aggregate == null) {
            return failure;
        }

        if (aggregate == failure) {
            return aggregate;
        }

        if (failure instanceof Error && !(aggregate instanceof Error)) {
            failure.addSuppressed(aggregate);
            return failure;
        }

        aggregate.addSuppressed(failure);
        return aggregate;
    }

    public static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }

        throw (RuntimeException) failure;
    }
}
