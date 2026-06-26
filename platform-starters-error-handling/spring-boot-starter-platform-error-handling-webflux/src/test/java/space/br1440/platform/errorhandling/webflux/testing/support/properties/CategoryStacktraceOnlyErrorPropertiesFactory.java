package space.br1440.platform.errorhandling.webflux.testing.support.properties;

import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.util.EnumSet;
import java.util.function.Supplier;

public final class CategoryStacktraceOnlyErrorPropertiesFactory implements Supplier<ErrorHandlingProperties> {

    @Override
    public ErrorHandlingProperties get() {
        ErrorHandlingProperties props = DefaultPropertiesFactory.baseline();
        props.setStacktraceLogLevels(EnumSet.of(LogPolicy.ERROR));
        return props;
    }
}
