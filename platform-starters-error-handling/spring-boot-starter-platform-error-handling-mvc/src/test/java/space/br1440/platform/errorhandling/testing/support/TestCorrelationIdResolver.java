package space.br1440.platform.errorhandling.testing.support;

import space.br1440.platform.errorhandling.core.util.CorrelationIdResolver;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

/**
 * Утилита для тестов: дефолтный экземпляр {@link CorrelationIdResolver}.
 */
public final class TestCorrelationIdResolver {

    private TestCorrelationIdResolver() {
        throw new AssertionError("test-only utility");
    }

    public static CorrelationIdResolver defaultInstance() {
        return new CorrelationIdResolver(
                new ErrorHandlingProperties.CorrelationId(),
                true
        );
    }
}
