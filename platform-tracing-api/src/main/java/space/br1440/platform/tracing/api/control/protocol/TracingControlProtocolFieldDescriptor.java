package space.br1440.platform.tracing.api.control.protocol;

import java.util.Objects;

record TracingControlProtocolFieldDescriptor(String key, TracingControlProtocolFieldType type) {

    TracingControlProtocolFieldDescriptor {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
    }
}
