package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Objects;
import java.util.Set;

record AgentReadinessSnapshot(Protocol protocol,
                              Status status,
                              Capabilities capabilities,
                              InstallFlags installFlags) {

    AgentReadinessSnapshot {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(installFlags, "installFlags");
    }

    record Protocol(String extensionVersion, int protocolVersion, String profile) {
        Protocol {
            extensionVersion = normalize(extensionVersion);
            profile = normalize(profile);
        }
    }

    record Status(String lifecycle, String failureCode) {
        Status {
            lifecycle = normalize(lifecycle);
            failureCode = normalize(failureCode);
        }
    }

    record Capabilities(Set<String> advertised, Set<String> required) {
        Capabilities {
            advertised = (advertised == null) ? Set.of() : Set.copyOf(advertised);
            required = (required == null) ? Set.of() : Set.copyOf(required);
        }

        boolean mismatchesRequired() {
            return !advertised.equals(required);
        }
    }

    record InstallFlags(boolean sanitizerInstalled,
                        boolean samplerInstalled,
                        boolean requiredSpanProcessorsInstalled,
                        boolean propagationHooksInstalled,
                        boolean exportPathProtected) {

        static InstallFlags complete() {
            return new InstallFlags(true, true, true, true, true);
        }

        static InstallFlags absent() {
            return new InstallFlags(false, false, false, false, false);
        }

        boolean isComplete() {
            return sanitizerInstalled
                    && samplerInstalled
                    && requiredSpanProcessorsInstalled
                    && propagationHooksInstalled
                    && exportPathProtected;
        }
    }

    private static String normalize(String value) {
        return (value == null) ? "" : value;
    }
}
