package space.br1440.platform.datasource.it;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;
import space.br1440.platform.datasource.support.assertj.RoutingAssertions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Конкурентный поток smoke: 64 виртуальных потока ходят попеременно writeOps и readOps;
 * проверяется, что counters растут согласно intent.
 */
@DisplayName("RoutingDataSource: конкурентность")
class ConcurrentClusterIT extends AbstractClusterIntegrationTest {

    @Override
    protected Class<?> applicationClass() {
        return ConcurrentApp.class;
    }

    @Test
    @DisplayName("64 потока × смешанная нагрузка: counters точно отражают распределение")
    void concurrentReadWrite_countersAccurate() throws Exception {
        DataSource ds = context.getBean(DataSource.class);
        MeterRegistry meters = context.getBean(MeterRegistry.class);

        // initialize
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS conc_test (id SERIAL PRIMARY KEY, v INT)");
            st.execute("TRUNCATE TABLE conc_test");
        }

        int writes = 50;
        int reads = 80;

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<?>[] futures = new CompletableFuture<?>[writes + reads];
            int idx = 0;
            for (int i = 0; i < writes; i++) {
                final int n = i;
                futures[idx++] = CompletableFuture.runAsync(
                        () -> performWrite(context.getBean(WriteService.class), n), pool);
            }
            for (int i = 0; i < reads; i++) {
                futures[idx++] = CompletableFuture.runAsync(
                        () -> performRead(context.getBean(ReadService.class)), pool);
            }
            CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);
        }

        RoutingAssertions.assertThat(meters)
                .hasCounterValueAtLeast("datasource.routing.requests",
                        "db.routing.target", "write", writes)
                .hasCounterValueAtLeast("datasource.routing.requests",
                        "db.routing.target", "read", reads);
    }

    private void performWrite(WriteService svc, int n) {
        try {
            svc.insert(n);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void performRead(ReadService svc) {
        boolean inRecovery = svc.isInRecovery();
        assertThat(inRecovery).isTrue();
    }

    @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    public static class ConcurrentApp {
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
        @Bean WriteService writeService(DataSource ds) { return new WriteService(ds); }
        @Bean ReadService readService(DataSource ds) { return new ReadService(ds); }
    }

    public static class WriteService {
        private final DataSource ds;
        WriteService(DataSource ds) { this.ds = ds; }

        @Transactional
        public void insert(int n) {
            try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
                st.execute("INSERT INTO conc_test (v) VALUES (" + n + ")");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class ReadService {
        private final DataSource ds;
        ReadService(DataSource ds) { this.ds = ds; }

        @Transactional(readOnly = true)
        public boolean isInRecovery() {
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement();
                 var rs = st.executeQuery("SELECT pg_is_in_recovery()")) {
                rs.next();
                return rs.getBoolean(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
