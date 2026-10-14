package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;

record DecodedSettingsUpdate(SettingsUpdateAttempt attempt,
                             SettingsRevision expectedBase,
                             TracingSettings settings) {
}
