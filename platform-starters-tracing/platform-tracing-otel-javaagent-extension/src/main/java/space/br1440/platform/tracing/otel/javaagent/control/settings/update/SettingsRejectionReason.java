package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

public enum SettingsRejectionReason {
    NONE,
    UNKNOWN_FIELD,
    MISSING_REQUIRED_FIELD,
    TYPE_MISMATCH,
    INVALID_VALUE,
    LIMIT_EXCEEDED,
    RUNTIME_INSTANCE_MISMATCH,
    ACCEPTED_SEQUENCE_MISMATCH,
    STRICT_RUNTIME_NOT_ALLOWED,
    INTERNAL_FAILURE
}
