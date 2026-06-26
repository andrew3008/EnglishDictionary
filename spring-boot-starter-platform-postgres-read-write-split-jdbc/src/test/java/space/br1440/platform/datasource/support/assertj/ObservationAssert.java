package space.br1440.platform.datasource.support.assertj;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.assertj.core.api.AbstractAssert;
import space.br1440.platform.datasource.postgres.internal.integration.observation.RoutingObservationConvention;

/**
 * Adapter поверх Micrometer {@link TestObservationRegistryAssert}.
 * <p>
 * Несёт две ответственности:
 * <ul>
 *   <li>встраивается в единый fluent-API {@link RoutingAssertions};</li>
 *   <li>дает синтаксический сахар для типичных сценариев маршрутизации
 *       (имя observation = {@link RoutingObservationConvention#OBSERVATION_NAME},
 *       тег {@link RoutingObservationConvention#KEY_DB_ROUTING_TARGET}).</li>
 * </ul>
 */
public class ObservationAssert extends AbstractAssert<ObservationAssert, TestObservationRegistry> {

    public ObservationAssert(TestObservationRegistry registry) {
        super(registry, ObservationAssert.class);
    }

    /** Делегирует к Micrometer-tck для сложных проверок (события, parent, error). */
    public TestObservationRegistryAssert delegateAssert() {
        return TestObservationRegistryAssert.assertThat(actual);
    }

    public ObservationAssert hasObservationWithName(String name) {
        isNotNull();
        delegateAssert().hasObservationWithNameEqualTo(name);
        return this;
    }

    public ObservationAssert hasObservationWithRoutingTarget(String target) {
        isNotNull();
        delegateAssert().hasObservationWithNameEqualTo(RoutingObservationConvention.OBSERVATION_NAME)
                .that()
                .hasLowCardinalityKeyValue(RoutingObservationConvention.KEY_DB_ROUTING_TARGET, target);
        return this;
    }

    public ObservationAssert hasObservationWithError() {
        isNotNull();
        delegateAssert().hasObservationWithNameEqualTo(RoutingObservationConvention.OBSERVATION_NAME)
                .that()
                .hasError();
        return this;
    }

    public ObservationAssert hasNoObservations() {
        isNotNull();
        delegateAssert().doesNotHaveAnyObservation();
        return this;
    }
}
