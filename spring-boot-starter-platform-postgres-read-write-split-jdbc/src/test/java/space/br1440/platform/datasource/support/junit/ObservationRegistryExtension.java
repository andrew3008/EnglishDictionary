package space.br1440.platform.datasource.support.junit;

import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 Extension: предоставляет per-test изолированный {@link TestObservationRegistry}
 * для проверок Micrometer Observation API.
 * <p>
 * Тесты могут принимать его как параметр:
 * <pre>{@code
 * @ExtendWith(ObservationRegistryExtension.class)
 * class ObservationTest {
 *     @Test
 *     void shouldStartObservation(TestObservationRegistry registry) {
 *         // ... when ...
 *         RoutingAssertions.assertThat(registry).hasObservationWithName("datasource.routing");
 *     }
 * }
 * }</pre>
 */
public class ObservationRegistryExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(ObservationRegistryExtension.class);
    private static final String STORE_KEY = "test-observation-registry";

    @Override
    public void beforeEach(ExtensionContext context) {
        TestObservationRegistry registry = TestObservationRegistry.create();
        // ObservationHandler.AllMatchingCompositeObservationHandler — здесь не нужен;
        // TestObservationRegistry уже предоставляет captured observations через assertions API.
        context.getStore(NAMESPACE).put(STORE_KEY, registry);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TestObservationRegistry registry =
                context.getStore(NAMESPACE).remove(STORE_KEY, TestObservationRegistry.class);
        if (registry != null) {
            registry.clear();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        return type == TestObservationRegistry.class || type == ObservationRegistry.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        TestObservationRegistry registry =
                extensionContext.getStore(NAMESPACE).get(STORE_KEY, TestObservationRegistry.class);
        if (registry == null) {
            throw new ParameterResolutionException("ObservationRegistry not initialized");
        }
        return registry;
    }

    /**
     * Удобный helper: добавляет no-op handler для observation registry —
     * нужен, если тест полагается на побочные эффекты handler-чейна.
     */
    public static void addNoopHandler(ObservationRegistry registry) {
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                return true;
            }
        });
    }
}
