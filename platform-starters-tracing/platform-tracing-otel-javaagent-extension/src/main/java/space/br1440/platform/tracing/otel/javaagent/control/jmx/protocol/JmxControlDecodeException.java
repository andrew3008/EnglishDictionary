package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import java.io.Serial;
import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;

final class JmxControlDecodeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final SettingsRejectionReason messageCode;
    private final String propertyName;

    JmxControlDecodeException(SettingsRejectionReason messageCode, String message) {
        this(messageCode, null, message);
    }

    JmxControlDecodeException(SettingsRejectionReason messageCode,
                              String propertyName,
                              String message) {
        super(message);
        this.messageCode = Objects.requireNonNull(messageCode, "messageCode");
        this.propertyName = propertyName;
    }

    SettingsRejectionReason messageCode() {
        return messageCode;
    }

    String propertyName() {
        return propertyName;
    }
}
