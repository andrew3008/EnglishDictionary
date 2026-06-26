package space.br1440.platform.datasource.it.support;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JpaOrderRepository extends JpaRepository<JpaOrderFixtures.Order, Long> {

    @Query(value = "SELECT pg_is_in_recovery()", nativeQuery = true)
    Boolean isInRecovery();
}
