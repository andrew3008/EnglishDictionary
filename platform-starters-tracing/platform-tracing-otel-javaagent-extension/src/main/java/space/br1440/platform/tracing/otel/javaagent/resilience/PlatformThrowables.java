package space.br1440.platform.tracing.otel.javaagent.resilience;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class PlatformThrowables {

    @SuppressWarnings({"removal"})
    public static void propagateIfFatal(Throwable t) {
        switch (t) {
            case VirtualMachineError virtualMachineError -> throw virtualMachineError;
            case ThreadDeath threadDeath -> throw threadDeath;
            case LinkageError linkageError -> throw linkageError;
            case InterruptedException ignored -> Thread.currentThread().interrupt();
            case null, default -> {
            }
        }
    }

    public static void propagateFatalCauseChain(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            propagateIfFatal(current);

            Throwable next = current.getCause();
            if (next == current) {
                return;
            }

            current = next;
        }
    }
}
