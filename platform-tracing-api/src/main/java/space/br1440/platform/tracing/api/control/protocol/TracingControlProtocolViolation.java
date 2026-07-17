package space.br1440.platform.tracing.api.control.protocol;

public record TracingControlProtocolViolation(
        String key,
        String reason,
        String expectedType,
        String actualType,
        TracingControlProtocolViolationCode code) {
}
