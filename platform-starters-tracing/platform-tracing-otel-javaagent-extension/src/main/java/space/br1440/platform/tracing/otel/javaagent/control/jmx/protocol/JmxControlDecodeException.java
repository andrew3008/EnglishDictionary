package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;

final class JmxControlDecodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final SettingsRejectionReason messageCode;

    JmxControlDecodeException(SettingsRejectionReason messageCode, String message) {
        super(message);
        this.messageCode = Objects.requireNonNull(messageCode, "messageCode");
    }

    SettingsRejectionReason messageCode() {
        return messageCode;
    }
}
