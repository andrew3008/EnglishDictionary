package space.br1440.platform.errorhandling.webflux.testing.support.properties;

import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.util.function.Supplier;

public final class DefaultPropertiesFactory implements Supplier<ErrorHandlingProperties> {

    @Override
    public ErrorHandlingProperties get() {
        return baseline();
    }

    public static ErrorHandlingProperties baseline() {
        return new ErrorHandlingProperties();
    }
}
