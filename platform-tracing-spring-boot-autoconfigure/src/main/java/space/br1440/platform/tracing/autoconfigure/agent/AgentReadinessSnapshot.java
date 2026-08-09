package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Objects;
import java.util.Set;

record AgentReadinessSnapshot(Protocol protocol,
                              Status status,
                              Set<String> capabilities) {

    AgentReadinessSnapshot {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(status, "status");
        capabilities = (capabilities == null) ? Set.of() : Set.copyOf(capabilities);
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

    private static String normalize(String value) {
        return (value == null) ? "" : value;
    }
}
