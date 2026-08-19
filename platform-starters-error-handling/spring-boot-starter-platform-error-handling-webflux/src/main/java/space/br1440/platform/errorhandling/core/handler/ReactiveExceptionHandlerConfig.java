package space.br1440.platform.errorhandling.core.handler;

import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingEnricherProperties;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingSafetyNetProperties;

import java.util.Objects;

public record ReactiveExceptionHandlerConfig(@NonNull ErrorHandlingProperties properties,
                                             @NonNull ErrorHandlingEnricherProperties enricherProperties,
                                             @NonNull ErrorHandlingSafetyNetProperties safetyNetProperties) {
    public ReactiveExceptionHandlerConfig {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(enricherProperties, "enricherProperties");
        Objects.requireNonNull(safetyNetProperties, "safetyNetProperties");
    }
}
