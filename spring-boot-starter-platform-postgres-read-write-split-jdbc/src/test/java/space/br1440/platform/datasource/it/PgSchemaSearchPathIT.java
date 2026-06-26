package space.br1440.platform.datasource.it;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.support.junit.ClusterRoutingItProperties;
import space.br1440.platform.datasource.support.junit.PgClusterExtension;
import space.br1440.platform.datasource.support.junit.PgClusterExtension.PgCluster;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 Testcontainers IT: verifies actual PostgreSQL {@code SHOW search_path} behaviour
 * with and without {@code PG_SCHEMA} / {@code jdbc.properties.currentSchema}.
 *
 * <ul>
 *   <li><b>Without PG_SCHEMA</b>: PostgreSQL server default {@code search_path} is preserved
 *       (typically {@code "$user", public}); the starter does NOT inject {@code currentSchema=public},
 *       which would remove {@code $user} and {@code pg_temp} from the path — a breaking change.</li>
 *   <li><b>With PG_SCHEMA</b>: {@code SHOW search_path} returns the configured schema.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(PgClusterExtension.class)
@DisplayName("PG_SCHEMA / currentSchema → actual SHOW search_path on live PostgreSQL")
class PgSchemaSearchPathIT {

    private ConfigurableApplicationContext contextWithoutSchema;
    private ConfigurableApplicationContext contextWithSchema;

    private static final String TEST_SCHEMA = "pgschema_it_test";

    @BeforeAll
    void startContexts(PgCluster cluster) throws Exception {
        // Prepare the schema on primary before starting contexts
        cluster.execPsql("CREATE SCHEMA IF NOT EXISTS " + TEST_SCHEMA + ";");

        contextWithoutSchema = startContext(cluster, null);
        contextWithSchema = startContext(cluster, TEST_SCHEMA);
    }

    @AfterAll
    void stopContexts() {
        if (contextWithoutSchema != null && contextWithoutSchema.isActive()) contextWithoutSchema.close();
        if (contextWithSchema != null && contextWithSchema.isActive()) contextWithSchema.close();
    }

    @Test
    @DisplayName("без PG_SCHEMA — search_path не зафиксирован стартером, сервер возвращает default")
    void withoutPgSchema_searchPathIsServerDefault() throws Exception {
        HikariDataSource write = (HikariDataSource) contextWithoutSchema.getBean("writeDataSource");

        // Critically, the starter must NOT inject currentSchema=public when PG_SCHEMA is absent,
        // which would override $user in the server's default search_path.
        assertThat(write.getJdbcUrl()).doesNotContain("currentSchema");

        // Verify the connection works and the server returns some search_path
        try (Connection conn = write.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW search_path")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isNotBlank();
        }
    }

    @Test
    @DisplayName("с PG_SCHEMA=" + TEST_SCHEMA + " — SHOW search_path возвращает настроенную схему")
    void withPgSchema_searchPathMatchesConfiguredSchema() throws Exception {
        HikariDataSource write = (HikariDataSource) contextWithSchema.getBean("writeDataSource");

        // Verify the URL was built with the correct currentSchema
        assertThat(write.getJdbcUrl()).contains("currentSchema=" + TEST_SCHEMA);

        try (Connection conn = write.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW search_path")) {
            assertThat(rs.next()).isTrue();
            String searchPath = rs.getString(1);
            // pgJDBC sets search_path on connection establishment;
            // SHOW search_path should contain the schema name
            assertThat(searchPath).contains(TEST_SCHEMA);
        }
    }

    @Test
    @DisplayName("с PG_SCHEMA — URL содержит currentSchema, без PG_SCHEMA — не содержит")
    void urlContainsCurrentSchemaOnlyWhenSchemaConfigured() {
        HikariDataSource writeWithout = (HikariDataSource) contextWithoutSchema.getBean("writeDataSource");
        HikariDataSource writeWith = (HikariDataSource) contextWithSchema.getBean("writeDataSource");

        assertThat(writeWithout.getJdbcUrl()).doesNotContain("currentSchema");
        assertThat(writeWith.getJdbcUrl()).contains("currentSchema=" + TEST_SCHEMA);
    }

    private ConfigurableApplicationContext startContext(PgCluster cluster, String schema) {
        Map<String, Object> properties = new HashMap<>(
                ClusterRoutingItProperties.baseClusterContextProperties(cluster));
        properties.put(PgClusterProperties.PREFIX + ".pool.write.maximum-pool-size", "3");
        properties.put(PgClusterProperties.PREFIX + ".pool.read.maximum-pool-size", "3");

        if (schema != null) {
            properties.put(PgClusterProperties.PREFIX + ".jdbc.properties.currentSchema", schema);
        }

        SpringApplication app = new SpringApplication(SchemaTestApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(properties);
        return app.run();
    }

    @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    public static class SchemaTestApp {
    }
}
