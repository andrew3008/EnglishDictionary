package space.br1440.platform.tracing.otel.javaagent.resilience;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class PlatformThrowables {

    private static final int MAX_CAUSE_CHAIN_DEPTH = 16;

    @SuppressWarnings({"removal"})
    public static boolean isFatal(Throwable t) {
        return t instanceof VirtualMachineError
                || t instanceof ThreadDeath
                || t instanceof LinkageError;
    }

    public static void propagateIfFatal(Throwable t) {
        switch (t) {
            case InterruptedException ignored -> Thread.currentThread().interrupt();
            case Error error when isFatal(error) -> throw error;
            case null, default -> {
            }
        }
    }

    public static Throwable firstFatalInCauseChain(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_CHAIN_DEPTH; depth++) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            if (isFatal(current)) {
                return current;
            }

            Throwable next;
            try {
                next = current.getCause();
            } catch (RuntimeException | Error accessorFailure) {
                return isFatal(accessorFailure) ? accessorFailure : null;
            }

            if (next == current) {
                return null;
            }

            current = next;
        }

        return null;
    }

    public static void propagateFatalCauseChain(Throwable failure) {
        Throwable fatal = firstFatalInCauseChain(failure);
        if (fatal != null) {
            throw (Error) fatal;
        }
    }
}
