package space.br1440.platform.tracing.api.control.protocol.result;

import space.br1440.platform.tracing.api.control.protocol.validation.TracingControlProtocolViolationCode;

public record TracingControlProtocolViolation(
        String key,
        String reason,
        String expectedType,
        String actualType,
        TracingControlProtocolViolationCode code) {
}
