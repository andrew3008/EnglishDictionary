package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;

final class PrivateControlDecodeException extends RuntimeException {

    private final SettingsRejectionReason messageCode;

    PrivateControlDecodeException(SettingsRejectionReason messageCode, String message) {
        super(message);
        this.messageCode = messageCode;
    }

    SettingsRejectionReason messageCode() {
        return messageCode;
    }
}
