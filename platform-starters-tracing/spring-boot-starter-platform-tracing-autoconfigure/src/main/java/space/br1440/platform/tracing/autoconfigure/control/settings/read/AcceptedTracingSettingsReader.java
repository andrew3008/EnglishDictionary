package space.br1440.platform.tracing.autoconfigure.control.settings.read;

import java.util.Optional;

public interface AcceptedTracingSettingsReader {

    Optional<AcceptedSettingsReadModel> currentReadModel();

}
