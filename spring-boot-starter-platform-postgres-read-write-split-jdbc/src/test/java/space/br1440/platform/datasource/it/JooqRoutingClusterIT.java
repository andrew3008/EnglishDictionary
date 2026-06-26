package space.br1440.platform.datasource.it;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.datasource.postgres.api.jooq.RoutingDSLContextFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jOOQ + RoutingDSLContextFactory: вызовы {@code read(..)} идут на replica
 * (через DataSourceContextHolder), {@code write(..)} — на master.
 */
@DisplayName("RoutingDSLContextFactory × jOOQ × живой кластер")
class JooqRoutingClusterIT extends AbstractClusterIntegrationTest {

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of(
                "spring.jooq.sql-dialect", "POSTGRES"
        );
    }

    @Override
    protected Class<?> applicationClass() {
        return JooqApp.class;
    }

    @BeforeAll
    void initSchema() throws Exception {
        try (Connection conn = ((DataSource) context.getBean("writeDataSource")).getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS jooq_test (id SERIAL PRIMARY KEY)");
        }
    }

    @Test
    @DisplayName("write(..) идёт на primary (pg_is_in_recovery=false)")
    void write_onPrimary() {
        RoutingDSLContextFactory factory = context.getBean(RoutingDSLContextFactory.class);
        Boolean inRecovery = factory.<Boolean>write(dsl -> {
            Object value = dsl.fetchValue("SELECT pg_is_in_recovery()", Boolean.class);
            return (Boolean) value;
        });
        assertThat(inRecovery)
                .as("write() must reach primary")
                .isFalse();
    }

    @Test
    @DisplayName("read(..) идёт на replica (pg_is_in_recovery=true)")
    void read_onReplica() {
        RoutingDSLContextFactory factory = context.getBean(RoutingDSLContextFactory.class);
        Boolean inRecovery = factory.<Boolean>read(dsl -> {
            Object value = dsl.fetchValue("SELECT pg_is_in_recovery()", Boolean.class);
            return (Boolean) value;
        });
        assertThat(inRecovery)
                .as("read() must reach replica")
                .isTrue();
    }

    @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    public static class JooqApp {
        @Bean DSLContext dslContext(DataSource ds) {
            return DSL.using(ds, SQLDialect.POSTGRES);
        }
    }
}
