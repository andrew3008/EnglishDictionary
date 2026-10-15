package space.br1440.platform.tracing.autoconfigure.status;

import java.util.Optional;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;

@FunctionalInterface
public interface AcceptedSettingsStatusReader {

    Optional<AcceptedSettings> current();
}
