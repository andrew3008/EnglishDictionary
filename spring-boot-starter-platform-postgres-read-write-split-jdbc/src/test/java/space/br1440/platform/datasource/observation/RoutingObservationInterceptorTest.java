package space.br1440.platform.datasource.postgres.internal.integration.observation;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;
import space.br1440.platform.datasource.support.assertj.RoutingAssertions;
import space.br1440.platform.datasource.support.junit.MdcCleanupExtension;
import space.br1440.platform.datasource.support.junit.ObservationRegistryExtension;

import javax.sql.DataSource;

import static org.mockito.Mockito.mock;

@DisplayName("RoutingObservationInterceptor")
@ExtendWith({ObservationRegistryExtension.class, MdcCleanupExtension.class})
class RoutingObservationInterceptorTest {

    private RoutingObservationInterceptor interceptor;
    private TestObservationRegistry registry;

    @BeforeEach
    void setUp(TestObservationRegistry registry) {
        this.registry = registry;
        this.interceptor = new RoutingObservationInterceptor(
                registry, new RoutingObservationConvention.Default());
    }

    @Nested
    @DisplayName("createObservation()")
    class CreateObservation {

        @Test
        @DisplayName("должен создать observation с тегом db.routing.target=write для WRITE intent")
        void shouldCreateWriteObservation() {
            HikariDataSource hds = newHikari("postgres-cluster-write-pool");
            try {
                Observation obs = interceptor.createObservation(DataSourceType.WRITE, hds);
                obs.start().stop();

                RoutingAssertions.assertThat(registry)
                        .hasObservationWithName(RoutingObservationConvention.OBSERVATION_NAME)
                        .hasObservationWithRoutingTarget("write");
            } finally {
                hds.close();
            }
        }

        @Test
        @DisplayName("должен создать observation с тегом db.routing.target=read для READ intent")
        void shouldCreateReadObservation() {
            HikariDataSource hds = newHikari("postgres-cluster-read-pool");
            try {
                Observation obs = interceptor.createObservation(DataSourceType.READ, hds);
                obs.start().stop();

                RoutingAssertions.assertThat(registry)
                        .hasObservationWithName(RoutingObservationConvention.OBSERVATION_NAME)
                        .hasObservationWithRoutingTarget("read");
            } finally {
                hds.close();
            }
        }

        @Test
        @DisplayName("должен использовать pool name для тега db.connection.pool.name")
        void shouldIncludePoolNameTag() {
            HikariDataSource hds = newHikari("special-pool-name");
            try {
                Observation obs = interceptor.createObservation(DataSourceType.WRITE, hds);
                obs.start().stop();

                RoutingAssertions.assertThat(registry)
                        .delegateAssert()
                        .hasObservationWithNameEqualTo(RoutingObservationConvention.OBSERVATION_NAME)
                        .that()
                        .hasLowCardinalityKeyValue(
                                RoutingObservationConvention.KEY_DB_CONNECTION_POOL_NAME, "special-pool-name");
            } finally {
                hds.close();
            }
        }

        @Test
        @DisplayName("должен использовать 'unknown' для не-Hikari DataSource")
        void shouldDefaultPoolNameForNonHikari() {
            DataSource ds = mock(DataSource.class);
            Observation obs = interceptor.createObservation(DataSourceType.WRITE, ds);
            obs.start().stop();

            RoutingAssertions.assertThat(registry)
                    .delegateAssert()
                    .hasObservationWithNameEqualTo(RoutingObservationConvention.OBSERVATION_NAME)
                    .that()
                    .hasLowCardinalityKeyValue(
                            RoutingObservationConvention.KEY_DB_CONNECTION_POOL_NAME,
                            RoutingObservationConvention.UNKNOWN_POOL);
        }

        @Test
        @DisplayName("должен фиксировать error через obs.error()")
        void shouldRecordError() {
            HikariDataSource hds = newHikari("err-pool");
            try {
                Observation obs = interceptor.createObservation(DataSourceType.WRITE, hds).start();
                obs.error(new RuntimeException("simulated"));
                obs.stop();

                RoutingAssertions.assertThat(registry)
                        .delegateAssert()
                        .hasObservationWithNameEqualTo(RoutingObservationConvention.OBSERVATION_NAME)
                        .that()
                        .hasError();
            } finally {
                hds.close();
            }
        }
    }

    private HikariDataSource newHikari(String poolName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:test_" + poolName + ";DB_CLOSE_DELAY=-1");
        config.setPoolName(poolName);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }
}
