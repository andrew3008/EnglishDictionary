package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class BootstrapCleanupFailures {

    private static final String UNKNOWN_RESOURCE_LABEL = "unknown";

    public static Throwable aggregate(Throwable aggregate, Throwable failure) {
        if (failure == null) {
            return aggregate;
        }

        if (aggregate == null) {
            return failure;
        }

        boolean promote = PlatformThrowables.isFatal(failure) && !PlatformThrowables.isFatal(aggregate);
        Throwable root = promote ? failure : aggregate;
        Throwable attached = promote ? aggregate : failure;
        try {
            attachIfAcyclic(root, attached);
            return root;
        } catch (RuntimeException | Error inspectionFailure) {
            if (PlatformThrowables.isFatal(root) || !PlatformThrowables.isFatal(inspectionFailure)) {
                return root;
            }

            attachBestEffort(inspectionFailure, root);
            return inspectionFailure;
        }
    }

    public static void suppressPreservingPrimary(Throwable primary, Throwable secondary) {
        attachIfAcyclic(
                Objects.requireNonNull(primary, "primary"),
                Objects.requireNonNull(secondary, "secondary")
        );
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
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current != null && visited.add(current)) {
            if (current instanceof BootstrapResourceCleanupException cleanupFailure) {
                return cleanupFailure.resourceLabel();
            }

            current = current.getCause();
        }

        return UNKNOWN_RESOURCE_LABEL;
    }

    public static boolean contains(Throwable root, Throwable target) {
        Reach reach = reach(
                Objects.requireNonNull(root, "root"),
                Objects.requireNonNull(target, "target")
        );
        return reach == Reach.FOUND;
    }

    private static void attachIfAcyclic(Throwable primary, Throwable secondary) {
        synchronized (BootstrapCleanupFailures.class) {
            if (reach(primary, secondary) == Reach.ABSENT && reach(secondary, primary) == Reach.ABSENT) {
                primary.addSuppressed(secondary);
            }
        }
    }

    private static void attachBestEffort(Throwable primary, Throwable secondary) {
        try {
            attachIfAcyclic(primary, secondary);
        } catch (RuntimeException | Error ignored) {
            // root уже выбран как fatal; повторный отказ inspection его не заменяет
        }
    }

    private static Reach reach(Throwable root, Throwable target) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.push(root);
        boolean complete = true;

        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (current == target) {
                return Reach.FOUND;
            }

            if (!visited.add(current)) {
                continue;
            }

            Throwable cause = null;
            try {
                cause = current.getCause();
            } catch (RuntimeException | Error accessorFailure) {
                if (PlatformThrowables.isFatal(accessorFailure)) {
                    throw accessorFailure;
                }

                complete = false;
            }

            if (cause != null) {
                pending.push(cause);
            }

            for (Throwable suppressed : current.getSuppressed()) {
                pending.push(suppressed);
            }
        }

        return complete ? Reach.ABSENT : Reach.UNKNOWN;
    }

    private enum Reach {
        FOUND,
        ABSENT,
        UNKNOWN
    }
}
