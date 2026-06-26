package space.br1440.platform.datasource.it;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import space.br1440.platform.datasource.support.assertj.RoutingAssertions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что Micrometer Observation реально создаётся для каждого вызова
 * {@code DataSource.getConnection()} через RoutingDataSource.
 */
@DisplayName("RoutingObservationInterceptor × живой кластер")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObservationClusterIT extends AbstractClusterIntegrationTest {

    @Override
    protected Class<?> applicationClass() {
        return ObservationApp.class;
    }

    @BeforeEach
    void clearObservations() {
        context.getBean(TestObservationRegistry.class).clear();
    }

    @Test
    @Order(1)
    @DisplayName("readOnly-транзакция → observation 'datasource.routing' с тегом target=read")
    void readOnlyTx_emitsReadObservation() throws Exception {
        TestObservationRegistry registry = context.getBean(TestObservationRegistry.class);
        DataSource ds = context.getBean(DataSource.class);
        TransactionTemplate readTx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        readTx.setReadOnly(true);

        // в read-only транзакции RoutingDataSource выбирает read-пул;
        // observation должен быть создан с тегом db.routing.target=read
        readTx.executeWithoutResult(status -> {
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement();
                 var rs = st.executeQuery("SELECT 1")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        RoutingAssertions.assertThat(registry)
                .hasObservationWithRoutingTarget("read");
    }

    @Test
    @Order(2)
    @DisplayName("Запись (без readOnly tx) → observation с тегом target=write")
    void writeTx_emitsWriteObservation() throws Exception {
        TestObservationRegistry registry = context.getBean(TestObservationRegistry.class);
        DataSource ds = context.getBean(DataSource.class);
        TransactionTemplate writeTx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        writeTx.executeWithoutResult(status -> {
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS obs_test (id SERIAL PRIMARY KEY)");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        RoutingAssertions.assertThat(registry)
                .hasObservationWithRoutingTarget("write");
    }

    @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    public static class ObservationApp {
        @Bean
        @Primary
        ObservationRegistry observationRegistry(TestObservationRegistry test) {
            return test;
        }

        @Bean
        TestObservationRegistry testObservationRegistry() {
            return TestObservationRegistry.create();
        }
    }
}
