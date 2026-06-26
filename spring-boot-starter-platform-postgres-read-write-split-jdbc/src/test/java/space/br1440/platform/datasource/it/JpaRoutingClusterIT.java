package space.br1440.platform.datasource.it;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import space.br1440.platform.datasource.it.support.JpaOrderFixtures;
import space.br1440.platform.datasource.it.support.JpaOrderFixtures.OrderService;
import space.br1440.platform.datasource.it.support.JpaOrderRepository;
import space.br1440.platform.datasource.support.assertj.RoutingAssertions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что Spring JPA + {@code @Transactional} корректно маршрутизирует:
 * <ul>
 *   <li>{@code @Transactional} (writable) → master (Primary)</li>
 *   <li>{@code @Transactional(readOnly=true)} → replica (Standby)</li>
 * </ul>
 */
@DisplayName("JPA × routing × живой кластер")
class JpaRoutingClusterIT extends AbstractClusterIntegrationTest {

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of(
                "spring.jpa.hibernate.ddl-auto", "create",
                "spring.jpa.show-sql", "false",
                "spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"
        );
    }

    @Override
    protected Class<?> applicationClass() {
        return JpaTestApp.class;
    }

    private OrderService service() {
        return context.getBean(OrderService.class);
    }

    @BeforeAll
    void seed() {
        try (Connection conn = ((DataSource) context.getBean("writeDataSource")).getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS orders (id SERIAL PRIMARY KEY, customer_id VARCHAR(64) NOT NULL)");
            stmt.execute("TRUNCATE TABLE orders");
            for (int i = 0; i < 5; i++) {
                stmt.execute("INSERT INTO orders (customer_id) VALUES ('c" + i + "')");
            }
        } catch (Exception e) {
            throw new RuntimeException("Seed failed", e);
        }
    }

    @Test
    @DisplayName("@Transactional → master (запись)")
    void writeTx_writesToMaster() {
        long initial = service().count();
        service().createOrder("new-customer");
        assertThat(service().count()).isEqualTo(initial + 1);
    }

    @Test
    @DisplayName("@Transactional(readOnly=true) → replica (pg_is_in_recovery=true)")
    void readOnlyTx_readsFromReplica() {
        boolean inRecovery = service().queryPgIsInRecovery();
        assertThat(inRecovery)
                .as("readOnly transaction must reach replica node where pg_is_in_recovery() is true")
                .isTrue();
    }

    @Test
    @DisplayName("Master DataSource НЕ readOnly, replica DataSource readOnly")
    void poolsHaveCorrectReadOnly() {
        com.zaxxer.hikari.HikariDataSource master =
                (com.zaxxer.hikari.HikariDataSource) context.getBean("writeDataSource");
        com.zaxxer.hikari.HikariDataSource replica =
                (com.zaxxer.hikari.HikariDataSource) context.getBean("readDataSource");

        RoutingAssertions.assertThat(master).isNotReadOnly();
        RoutingAssertions.assertThat(replica).isReadOnly();
    }

    @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    @EnableJpaRepositories(basePackageClasses = JpaOrderRepository.class)
    @EntityScan(basePackageClasses = JpaOrderFixtures.class)
    public static class JpaTestApp {
        @Bean
        OrderService orderService(JpaOrderRepository repo) {
            return new OrderService(repo);
        }
    }
}
