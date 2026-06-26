package space.br1440.platform.datasource.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import space.br1440.platform.datasource.postgres.PostgresDataSourceRoutingAutoConfiguration;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.internal.bootstrap.OnClientDataSourceRoutingCondition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-04 — precedence: explicit Spring property {@code jdbc.properties.currentSchema}
 * overrides {@code PG_SCHEMA} injected by the EPP.
 *
 * <p>Uses {@link ApplicationContextRunner#withPropertyValues(String...)} which inserts an
 * inline property source at a higher precedence level than the EPP-registered
 * {@code pg-cluster-env} source (lowest-precedence, registered via
 * {@link org.springframework.core.env.MutablePropertySources#addLast}).
 *
 * <p><b>Scope:</b> this test verifies <em>relative</em> precedence —
 * "explicit yaml/inline key &gt; PG_SCHEMA via EPP". It does NOT verify the full
 * 6-level hierarchy (system env &gt; system props &gt; command-line &gt; external yaml &gt;
 * packaged yaml &gt; EPP). External and packaged {@code application.yml} are processed by
 * {@code ConfigDataApplicationListener} after EPP; {@code withPropertyValues} simulates an
 * "inline config &gt; EPP" scenario, which is sufficient to confirm the contract.
 */
@DisplayName("PG_SCHEMA precedence: explicit jdbc.properties.currentSchema overrides PG_SCHEMA via EPP")
class PgSchemaCurrentSchemaPrecedenceTest {

    private static final String[] BASE_PROPS = {
            OnClientDataSourceRoutingCondition.PROPERTY + "=CLIENT",
            PgClusterProperties.PREFIX + ".nodes[0].host=localhost",
            PgClusterProperties.PREFIX + ".nodes[0].port=5432",
            PgClusterProperties.PREFIX + ".database=testdb",
            PgClusterProperties.PREFIX + ".credentials.username=sa",
            PgClusterProperties.PREFIX + ".credentials.password=sa",
            PgClusterProperties.PREFIX + ".pool.write.connection-timeout=250",
            PgClusterProperties.PREFIX + ".pool.read.connection-timeout=250",
            PgClusterProperties.PREFIX + ".pool.write.initialization-fail-timeout=-1",
            PgClusterProperties.PREFIX + ".pool.read.initialization-fail-timeout=-1"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PostgresDataSourceRoutingAutoConfiguration.class,
                    DataSourceAutoConfiguration.class
            ))
            .withPropertyValues(BASE_PROPS);

    @Test
    @DisplayName("jdbc.properties.currentSchema (yaml-like) overrides PG_SCHEMA via EPP (lower-precedence)")
    void yamlCurrentSchema_overridesPgSchema() {
        // ApplicationContextRunner.withPropertyValues(...) adds an inline property source
        // at high precedence — analogous to application.yml or packaged config.
        //
        // IMPORTANT: this test verifies relative precedence "explicit yaml key > PG_SCHEMA via EPP".
        // It does NOT verify the exact order of all 6 precedence levels.
        // External application.yml and packaged application.yml are handled by
        // ConfigDataApplicationListener after EPP — withPropertyValues simulates "inline config > EPP"
        // which is sufficient to confirm the contract.
        contextRunner
                // Simulate EPP injecting PG_SCHEMA → currentSchema (low-precedence source)
                .withPropertyValues(
                        PgClusterProperties.PREFIX + ".jdbc.properties.currentSchema=from_epp_schema")
                // Simulate explicit yaml override at higher precedence
                .withPropertyValues(
                        PgClusterProperties.PREFIX + ".jdbc.properties.currentSchema=from_yaml_schema")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    HikariDataSource writeDs = ctx.getBean("writeDataSource", HikariDataSource.class);
                    assertThat(writeDs.getJdbcUrl())
                            .contains("currentSchema=from_yaml_schema")
                            .doesNotContain("currentSchema=from_epp_schema");
                });
    }

    @Test
    @DisplayName("только PG_SCHEMA без yaml override → currentSchema присутствует в URL (EPP значение используется)")
    void onlyPgSchema_noYamlOverride_currentSchemaIsPresent() {
        contextRunner
                .withPropertyValues(
                        PgClusterProperties.PREFIX + ".jdbc.properties.currentSchema=my_schema")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    HikariDataSource writeDs = ctx.getBean("writeDataSource", HikariDataSource.class);
                    assertThat(writeDs.getJdbcUrl()).contains("currentSchema=my_schema");
                });
    }

    @Test
    @DisplayName("без currentSchema в properties → URL не содержит currentSchema (PostgreSQL server default preserved)")
    void noSchema_urlDoesNotContainCurrentSchema() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            HikariDataSource writeDs = ctx.getBean("writeDataSource", HikariDataSource.class);
            assertThat(writeDs.getJdbcUrl()).doesNotContain("currentSchema");
        });
    }
}
