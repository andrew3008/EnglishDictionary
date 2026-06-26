package space.br1440.platform.errorhandling.webflux.testing.handler;

import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;

import java.util.List;
import java.util.function.Supplier;

public final class NoStrategyFactorySupplier implements Supplier<List<TopExceptionHandlingStrategy>> {

    @Override
    public List<TopExceptionHandlingStrategy> get() {
        throw new UnsupportedOperationException("Маркер NOOP: не вызывать");
    }
}
