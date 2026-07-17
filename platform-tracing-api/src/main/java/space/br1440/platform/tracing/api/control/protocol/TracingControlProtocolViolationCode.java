package space.br1440.platform.tracing.api.control.protocol;

/**
 * Стабильные machine-readable коды структурного декодирования control protocol.
 */
public enum TracingControlProtocolViolationCode {

    UNSUPPORTED_VERSION,
    INVALID_VALUE,
    UNKNOWN_KEY,
    MISSING_REQUIRED_KEY,
    TYPE_MISMATCH,
    OPERATION_NOT_ALLOWED
}
