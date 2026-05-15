package space.br1440.platform.datasource.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.br1440.platform.datasource.support.assertj.RoutingAssertions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
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
        // создаём данные через master
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
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    public static class JpaTestApp {
        @Bean OrderService orderService(OrderRepository repo) { return new OrderService(repo); }
    }

    @Entity
    @Table(name = "orders")
    public static class Order {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "customer_id", nullable = false)
        private String customerId;

        public Long getId() { return id; }
        public String getCustomerId() { return customerId; }
        public void setCustomerId(String customerId) { this.customerId = customerId; }
    }

    public interface OrderRepository extends JpaRepository<Order, Long> {
        @org.springframework.data.jpa.repository.Query(
                value = "SELECT pg_is_in_recovery()", nativeQuery = true)
        Boolean isInRecovery();
    }

    @Service
    public static class OrderService {

        private final OrderRepository repo;

        public OrderService(OrderRepository repo) {
            this.repo = repo;
        }

        @Transactional
        public Order createOrder(String customerId) {
            Order o = new Order();
            o.setCustomerId(customerId);
            return repo.save(o);
        }

        @Transactional(readOnly = true)
        public boolean queryPgIsInRecovery() {
            return Boolean.TRUE.equals(repo.isInRecovery());
        }

        @Transactional(readOnly = true)
        public long count() {
            return repo.count();
        }

        @Transactional(readOnly = true)
        public List<Order> all() {
            return repo.findAll();
        }
    }

}
