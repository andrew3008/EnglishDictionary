package space.br1440.platform.tracing.autoconfigure.control.settings;

public final class TracingSettingsContractViolationException extends RuntimeException {

    public TracingSettingsContractViolationException(String message) {
        super(message);
    }

    public TracingSettingsContractViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
