package space.br1440.platform.tracing.autoconfigure.control.settings;

import java.util.Optional;

public interface AcceptedTracingSettingsReader {

    Optional<AcceptedSettings> current();

}
