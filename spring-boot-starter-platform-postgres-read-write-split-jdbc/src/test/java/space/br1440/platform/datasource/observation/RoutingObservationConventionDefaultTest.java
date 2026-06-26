package space.br1440.platform.datasource.postgres.internal.integration.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RoutingObservationConvention.Default")
class RoutingObservationConventionDefaultTest {

    private final RoutingObservationConvention convention = new RoutingObservationConvention.Default();

    @Test
    @DisplayName("getName() возвращает 'datasource.routing'")
    void shouldReturnConstantName() {
        assertThat(convention.getName()).isEqualTo("datasource.routing");
    }

    @Test
    @DisplayName("supportsContext() принимает только RoutingObservationContext")
    void shouldSupportOnlyRoutingContext() {
        RoutingObservationContext routingCtx = new RoutingObservationContext(DataSourceType.WRITE, "pool");
        Observation.Context plainCtx = new Observation.Context();

        assertThat(convention.supportsContext(routingCtx)).isTrue();
        assertThat(convention.supportsContext(plainCtx)).isFalse();
    }

    @Test
    @DisplayName("WRITE intent → tag db.routing.target=write + OTel tags")
    void shouldMapWriteIntentToWriteTag() {
        RoutingObservationContext ctx = new RoutingObservationContext(DataSourceType.WRITE, "p1");

        KeyValues tags = convention.getLowCardinalityKeyValues(ctx);

        assertThat(tags.stream())
                .extracting("key", "value")
                .contains(
                        org.assertj.core.groups.Tuple.tuple("db.system.name", "postgresql"),
                        org.assertj.core.groups.Tuple.tuple("db.routing.target", "write"),
                        org.assertj.core.groups.Tuple.tuple("db.connection.pool.name", "p1"));
    }

    @Test
    @DisplayName("READ intent → tag db.routing.target=read")
    void shouldMapReadIntentToReadTag() {
        RoutingObservationContext ctx = new RoutingObservationContext(DataSourceType.READ, "p2");

        KeyValues tags = convention.getLowCardinalityKeyValues(ctx);

        assertThat(tags.stream())
                .extracting("key", "value")
                .contains(org.assertj.core.groups.Tuple.tuple("db.routing.target", "read"));
    }

    @Test
    @DisplayName("Если poolName == null → тег db.connection.pool.name=unknown")
    void shouldDefaultPoolNameToUnknown() {
        RoutingObservationContext ctx = new RoutingObservationContext(DataSourceType.WRITE, null);

        KeyValues tags = convention.getLowCardinalityKeyValues(ctx);

        assertThat(tags.stream())
                .extracting("key", "value")
                .contains(org.assertj.core.groups.Tuple.tuple("db.connection.pool.name", "unknown"));
    }

    @Test
    @DisplayName("Convention интегрируется с no-op ObservationRegistry без NPE")
    void shouldNotFailWithNoopRegistry() {
        ObservationRegistry noop = ObservationRegistry.NOOP;
        Observation obs = Observation.createNotStarted(
                convention,
                () -> new RoutingObservationContext(DataSourceType.WRITE, "p"),
                noop);
        // ничего не должно упасть на старте/стопе
        obs.start().stop();
    }
}
