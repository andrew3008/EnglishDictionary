package space.br1440.platform.datasource.it.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** JPA fixture types for {@link space.br1440.platform.datasource.it.JpaRoutingClusterIT}. */
public final class JpaOrderFixtures {

    private JpaOrderFixtures() {
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

    public static class OrderService {

        private final JpaOrderRepository repo;

        public OrderService(JpaOrderRepository repo) {
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
