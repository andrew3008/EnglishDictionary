package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;

record DecodedSettingsUpdate(SettingsUpdateAttempt attempt,
                             ExpectedSettingsRevision expectedBase,
                             TracingSettings settings) {
}
