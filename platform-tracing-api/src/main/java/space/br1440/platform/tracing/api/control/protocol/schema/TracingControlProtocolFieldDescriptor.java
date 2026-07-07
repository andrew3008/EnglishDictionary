package space.br1440.platform.tracing.api.control.protocol.schema;

import java.util.Objects;
import java.util.Set;

public record TracingControlProtocolFieldDescriptor(String key,
                                                    TracingControlProtocolTypes type,
                                                    TracingControlProtocolFieldCategory category,
                                                    Set<TracingControlProtocolOperation> requiredForOperations) {

    public TracingControlProtocolFieldDescriptor {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(requiredForOperations, "requiredForOperations");

        requiredForOperations = Set.copyOf(requiredForOperations);
    }
}
