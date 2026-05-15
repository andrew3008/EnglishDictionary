package space.br1440.platform.errorhandling.webflux.testing.support.properties;

import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;

import java.util.function.Supplier;

public final class AllErrorLoggingPropertiesFactory implements Supplier<ErrorHandlingProperties> {

    @Override
    public ErrorHandlingProperties get() {
        ErrorHandlingProperties p = DefaultPropertiesFactory.baseline();
        p.setLoggingStrategy(LoggingStrategy.ALL_ERROR);
        return p;
    }
}
