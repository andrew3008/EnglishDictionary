package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class BootstrapCleanupFailures {

    private static final String UNKNOWN_RESOURCE_LABEL = "unknown";

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

        if (contains(aggregate, failure)) {
            return aggregate;
        }

        if (contains(failure, aggregate)) {
            return failure;
        }

        if (failure instanceof Error && !(aggregate instanceof Error)) {
            failure.addSuppressed(aggregate);
            return failure;
        }

        aggregate.addSuppressed(failure);
        return aggregate;
    }

    public static void suppressPreservingPrimary(Throwable primary, Throwable secondary) {
        Throwable requiredPrimary = Objects.requireNonNull(primary, "primary");
        Throwable requiredSecondary = Objects.requireNonNull(secondary, "secondary");
        if (contains(requiredPrimary, requiredSecondary) || contains(requiredSecondary, requiredPrimary)) {
            return;
        }

        requiredPrimary.addSuppressed(requiredSecondary);
    }

    public static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }

        throw (RuntimeException) failure;
    }

    public static Throwable withResourceLabel(String resourceLabel, Throwable failure) {
        if (failure instanceof Error || failure instanceof BootstrapResourceCleanupException) {
            return failure;
        }

        return new BootstrapResourceCleanupException(resourceLabel, failure);
    }

    public static String resourceLabel(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.push(failure);

        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (!visited.add(current)) {
                continue;
            }

            if (current instanceof BootstrapResourceCleanupException cleanupFailure) {
                return cleanupFailure.resourceLabel();
            }

            for (Throwable suppressed : current.getSuppressed()) {
                pending.push(suppressed);
            }

            Throwable cause = current.getCause();
            if (cause != null) {
                pending.push(cause);
            }
        }

        return UNKNOWN_RESOURCE_LABEL;
    }

    private static boolean contains(Throwable root, Throwable target) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.push(root);

        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (current == target) {
                return true;
            }

            if (!visited.add(current)) {
                continue;
            }

            Throwable cause = current.getCause();
            if (cause != null) {
                pending.push(cause);
            }

            for (Throwable suppressed : current.getSuppressed()) {
                pending.push(suppressed);
            }
        }

        return false;
    }
}
